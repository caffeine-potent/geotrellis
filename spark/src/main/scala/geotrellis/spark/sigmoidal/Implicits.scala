/*
 * Copyright (c) 2016 Azavea.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package geotrellis.spark.sigmoidal

import geotrellis.raster._
import geotrellis.spark._

import org.apache.spark.rdd.RDD


object Implicits extends Implicits

trait Implicits {

  implicit class withRDDSinglebandSigmoidalMethods[K, M](
    val self: RDD[(K, Tile)] with Metadata[M]
  ) extends RDDSinglebandSigmoidalMethods[K, M]

  implicit class withRDDMultibandSigmoidalMethods[K, M](
    val self: RDD[(K, MultibandTile)] with Metadata[M]
  ) extends RDDMultibandSigmoidalMethods[K, M]
}