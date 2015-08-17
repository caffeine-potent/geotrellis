package geotrellis.spark.io.cassandra.spacetime

import java.nio.ByteBuffer
import geotrellis.spark.io.avro.KeyCodecs._

import com.datastax.spark.connector.rdd.CassandraRDD
import geotrellis.raster._
import geotrellis.spark._
import geotrellis.spark.io.cassandra._
import geotrellis.spark.io.index._
import geotrellis.spark.io.index.zcurve._
import org.apache.spark.rdd.RDD
import org.joda.time.DateTime

import scala.collection.mutable

object SpaceTimeRasterRDDReader extends RasterRDDReader[SpaceTimeKey] {

  def index(tileLayout: TileLayout, keyBounds: KeyBounds[SpaceTimeKey]): KeyIndex[SpaceTimeKey] =
    ZSpaceTimeKeyIndex.byYear

  def tileSlugs(filters: List[GridBounds]): List[(String, String)] = filters match {
    case Nil =>
      List(("0"*6 + "_" + "0"*6) -> ("9"*6 + "_" + "9"*6))
    case _ =>
      for{
        bounds <- filters
        row <- bounds.rowMin to bounds.rowMax
      } yield f"${bounds.colMin}%06d_${row}%06d" -> f"${bounds.colMax}%06d_${row}%06d"
  }

  def timeSlugs(filters: List[(DateTime, DateTime)], minTime: DateTime, maxTime: DateTime): List[(Int, Int)] = filters match {
    case Nil =>
      List(timeChunk(minTime).toInt -> timeChunk(maxTime).toInt)
    case List((start, end)) =>
      List(timeChunk(start).toInt -> timeChunk(end).toInt)
  }

  def applyFilter(
    rdd: CassandraRDD[(String, ByteBuffer)],
    layerId: LayerId,
    queryKeyBounds: Seq[KeyBounds[SpaceTimeKey]],
    keyBounds: KeyBounds[SpaceTimeKey],
    index: KeyIndex[SpaceTimeKey]
  ): RDD[(String, ByteBuffer)] = {

    val spaceFilters = mutable.ListBuffer[GridBounds]()
    val timeFilters = mutable.ListBuffer[(DateTime, DateTime)]()

    val ranges = queryKeyBounds.map{ index.indexRanges(_) }.flatten
    logger.info(s"queryKeyBounds has ${ranges.length} ranges")

    for (range <- ranges) {
      logger.debug(s"range has ${range.toString()} ")
    }

    for ( qbounds <- queryKeyBounds ) {

      val spaceMinKey = qbounds.minKey.spatialKey
      val spaceMaxKey = qbounds.maxKey.spatialKey
      spaceFilters += GridBounds(spaceMinKey.col, spaceMinKey.row, spaceMaxKey.col, spaceMaxKey.row)
      logger.debug(s"qBounds GridBounds(${spaceMinKey.col}, ${spaceMinKey.row}, ${spaceMaxKey.col}, ${spaceMaxKey.row})")

      val timeMinKey = qbounds.minKey.temporalKey
      val timeMaxKey = qbounds.maxKey.temporalKey
      timeFilters += ( (timeMinKey.time, timeMaxKey.time) )
      logger.debug(s"qBounds ( (${timeMinKey.time.toString}, ${timeMaxKey.time.toString}) )")
    }

    if(spaceFilters.isEmpty) {
      val minKey = keyBounds.minKey.spatialKey
      val maxKey = keyBounds.maxKey.spatialKey
      spaceFilters += GridBounds(minKey.col, minKey.row, maxKey.col, maxKey.row)
      logger.debug(s"keyBounds GridBounds(${minKey.col}, ${minKey.row}, ${maxKey.col}, ${maxKey.row})")
    }

    if(timeFilters.isEmpty) {
      val minKey = keyBounds.minKey.temporalKey
      val maxKey = keyBounds.maxKey.temporalKey
      timeFilters += ( (minKey.time, maxKey.time) )
      logger.debug(s"keyBounds ( (${minKey.time.toString}, ${maxKey.time.toString}) )")
    }

    val rdds = mutable.ArrayBuffer[CassandraRDD[(String, ByteBuffer)]]()

    for {
      bounds <- spaceFilters
      (timeStart, timeEnd) <- timeFilters
    } yield {
      val p1 = SpaceTimeKey(bounds.colMin, bounds.rowMin, timeStart)
      val p2 = SpaceTimeKey(bounds.colMax, bounds.rowMax, timeEnd)

      val ranges = index.indexRanges(p1, p2)

      logger.debug(s"for yield has ${ranges.length} ranges")

      for (range <- ranges) {
        logger.debug(s"for yield range has ${range.toString()} ")
      }

      ranges
        .foreach { case (min: Long, max: Long) =>
          if (min == max)
            rdds += rdd.where("zoom = ? AND indexer = ?", layerId.zoom, min)
          else
            rdds += rdd.where("zoom = ? AND indexer >= ? AND indexer <= ?", layerId.zoom, min.toString, max.toString)
        }
    }

    // TODO: eventually find a more performant approach than union of thousands of RDDs
    rdd.context.union(rdds.toSeq).asInstanceOf[RDD[(String, ByteBuffer)]]
  }
}
