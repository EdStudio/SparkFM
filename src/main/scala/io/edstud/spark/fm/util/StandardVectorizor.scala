package io.edstud.spark.fm.util

import scala.collection.Map
import org.apache.spark.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext._
import breeze.linalg.SparseVector

class StandardVectorizor() extends Logging with Serializable {

    protected def identify(raw: RDD[Array[String]], definition: Map[Int, DataNode]): (RDD[String], RDD[Array[String]]) = {
        val dataset = raw.filter(features => features.size == definition.size).cache()

        val targets = dataset.map {
            features => features.zipWithIndex.filter(f => definition(f._2).isTarget).map(_._1).head
        }
        val inputs = dataset.map {
            features => features.zipWithIndex.filter(f => !definition(f._2).isTarget).map(_._1)
        }

        dataset.unpersist()

        (targets, inputs.cache)
    }

    protected def analyzeFeatures(inputs: RDD[Array[String]], definition: Map[Int, DataNode]): Map[Int, DataNode] = {
        definition.values.filter(!_.isTarget).zipWithIndex.map(_.swap).toMap.map { case (index, node) =>
            val stat = if (node.isIdentity || node.isList) {
                val feature = if (node.isList) {
                    inputs.flatMap(features => features(index).split(node.getSeperator))
                } else {
                    inputs.map(features => features(index))
                }

                val featureIds = feature.distinct.cache
                val featureIndexer = featureIds.zipWithIndex.collectAsMap
                val dimension = featureIds.count

                feature.unpersist()

                node.withIndexer(featureIndexer).withDimension(dimension)
            } else {
                node.withDimension(1)
            }

            (index, stat)
        }
    }

    protected def preprocess(inputs: RDD[Array[String]], nodes: Map[Int, DataNode]): RDD[Array[SparseVector[Double]]] = {
        inputs.map { features =>
            features.zipWithIndex.map {
                case (feature, index) => nodes(index).preprocess(feature)
            }
        }
    }

    protected def computeDimension(nodes: Map[Int, DataNode]): Int = {
        nodes.map(_._2.getDimension).reduce(_ + _).toInt
    }

    def transform(raw: RDD[Array[String]], definition: Map[Int, DataNode]): RDD[(Double, SparseVector[Double])] = {
        logInfo("Identifying Data Structure...")
        val (targets, inputs) = identify(raw, definition)

        logInfo("Analyzing features distribution...")
        val stats = analyzeFeatures(inputs, definition)

        logInfo("Preprocessing transformation...")
        val transformed = preprocess(inputs, stats)

        logInfo("Transformation in progress...")
        val dimension = computeDimension(stats)
        val featureVectors = transformed.map { array =>
            val f = SparseVector.zeros[Double](dimension)

            var offset = 0
            array.foreach { features =>
                features.activeIterator.foreach(pair => f.update(pair._1 + offset, pair._2))
                offset += features.size
            }

            f
        }

        targets.map(_.toDouble).zip(featureVectors)
    }

}