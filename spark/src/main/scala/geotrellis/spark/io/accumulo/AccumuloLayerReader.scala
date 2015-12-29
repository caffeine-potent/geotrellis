package geotrellis.spark.io.accumulo

import geotrellis.raster.{MultiBandTile, Tile}
import geotrellis.spark.io.avro._
import geotrellis.spark.io.avro.codecs._
import geotrellis.spark.io.json._
import geotrellis.spark._
import geotrellis.spark.io.index.KeyIndex
import geotrellis.spark.io._
import org.apache.avro.Schema
import org.apache.hadoop.io.Text
import org.apache.spark.SparkContext
import org.apache.accumulo.core.data.{Range => AccumuloRange}
import org.apache.spark.rdd.RDD
import spray.json._
import scala.reflect._

class AccumuloLayerReader[K: Boundable: AvroRecordCodec: JsonFormat: ClassTag, V: AvroRecordCodec: ClassTag, M: JsonFormat, C <: RDD[(K, V)]](
    val attributeStore: AttributeStore[JsonFormat],
    rddReader: BaseAccumuloRDDReader[K, V])
  (implicit sc: SparkContext, bridge: Bridge[(RDD[(K, V)], M), C])
  extends FilteringLayerReader[LayerId, K, M, C] {

  val defaultNumPartitions = sc.defaultParallelism

  def read(id: LayerId, rasterQuery: RDDQuery[K, M], numPartitions: Int) = {
    if (!attributeStore.layerExists(id)) throw new LayerNotFoundError(id)

    val (header, metaData, keyBounds, keyIndex, writerSchema) = try {
      attributeStore.readLayerAttributes[AccumuloLayerHeader, M, KeyBounds[K], KeyIndex[K], Schema](id)
    } catch {
      case e: AttributeNotFoundError => throw new LayerReadError(id).initCause(e)
    }

    val queryKeyBounds = rasterQuery(metaData, keyBounds)

    val decompose = (bounds: KeyBounds[K]) =>
      keyIndex.indexRanges(bounds).map { case (min, max) =>
        new AccumuloRange(new Text(long2Bytes(min)), new Text(long2Bytes(max)))
      }

    val rdd = rddReader.read(header.tileTable, columnFamily(id), queryKeyBounds, decompose, Some(writerSchema))
    bridge(rdd -> metaData)
  }
}

object AccumuloLayerReader {

  def apply[K: Boundable: AvroRecordCodec: JsonFormat: ClassTag, V: AvroRecordCodec: ClassTag, M: JsonFormat, C <: RDD[(K, V)]](instance: AccumuloInstance)
    (implicit sc: SparkContext, bridge: Bridge[(RDD[(K, V)], M), C]): AccumuloLayerReader[K, V, M, C] =
    new AccumuloLayerReader[K, V, M, C] (
      attributeStore = AccumuloAttributeStore(instance.connector),
      rddReader = new AccumuloRDDReader[K, V](instance)
    )

  def spatial(instance: AccumuloInstance)
    (implicit sc: SparkContext, bridge: Bridge[(RDD[(SpatialKey, Tile)], RasterMetaData), RasterRDD[SpatialKey]]) =
    apply[SpatialKey, Tile, RasterMetaData, RasterRDD[SpatialKey]](instance)

  def spatialMultiBand(instance: AccumuloInstance)
    (implicit sc: SparkContext, bridge: Bridge[(RDD[(SpatialKey, MultiBandTile)], RasterMetaData), MultiBandRasterRDD[SpatialKey]]) =
    apply[SpatialKey, MultiBandTile, RasterMetaData, MultiBandRasterRDD[SpatialKey]](instance)

  def spaceTime(instance: AccumuloInstance)
    (implicit sc: SparkContext, bridge: Bridge[(RDD[(SpaceTimeKey, Tile)], RasterMetaData), RasterRDD[SpaceTimeKey]]) =
    apply[SpaceTimeKey, Tile, RasterMetaData, RasterRDD[SpaceTimeKey]](instance)

  def spaceTimeMultiBand(instance: AccumuloInstance)
    (implicit sc: SparkContext, bridge: Bridge[(RDD[(SpaceTimeKey, MultiBandTile)], RasterMetaData), MultiBandRasterRDD[SpaceTimeKey]]) =
    apply[SpaceTimeKey, MultiBandTile, RasterMetaData, MultiBandRasterRDD[SpaceTimeKey]](instance)
}