package org.bdgenomics.guacamole.callers

import org.bdgenomics.guacamole._
import org.apache.spark.Logging
import org.bdgenomics.guacamole.Common.Arguments.{ TumorNormalReads, Output, Base }
import org.kohsuke.args4j.{ Option => Opt }
import org.bdgenomics.adam.cli.Args4j
import org.apache.spark.rdd.RDD
import org.bdgenomics.guacamole.pileup.Pileup
import scala.collection.{ mutable, JavaConversions }
import scala.Some
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.SparkContext._
import org.apache.spark.rdd._
import org.apache.spark.mllib.classification.{ LogisticRegressionModel, LogisticRegressionWithSGD }
import org.apache.hadoop.fs.{ FileSystem, Path }
import java.io.{ InputStreamReader, BufferedReader, OutputStreamWriter, BufferedWriter }
import org.apache.hadoop.conf.Configuration
import scala.collection.mutable.ArrayBuffer

object SomaticTrainableVariantCaller extends Command with Serializable with Logging {
  override val name = "somatic-trainable"
  override val description = "approximate an arbitrary somatic variant caller by training on its calls"

  private class Arguments extends Base with Output with TumorNormalReads with DistributedUtil.Arguments {
    @Opt(name = "-train-model-output", metaVar = "X",
      usage = "")
    var trainModelOutput: String = ""

    @Opt(name = "-train-loci-called", metaVar = "X",
      usage = "")
    var trainLociCalled: String = ""

    @Opt(name = "-train-num-iterations", metaVar = "X",
      usage = "")
    var trainNumIterations: Int = 100

    @Opt(name = "-predict-model-input", metaVar = "X",
      usage = "")
    var predictModelInput: String = ""
  }
  type LocusLabel = (Long, Long)

  override def run(rawArgs: Array[String]): Unit = {
    val args = Args4j[Arguments](rawArgs)
    val sc = Common.createSparkContext(args, appName = Some(name))

    val (rawTumorReads, tumorDictionary, rawNormalReads, normalDictionary) =
      Common.loadTumorNormalReadsFromArguments(args, sc, mapped = true, nonDuplicate = true)

    assert(tumorDictionary == normalDictionary,
      "Tumor and normal samples have different sequence dictionaries. Tumor dictionary: %s.\nNormal dictionary: %s."
        .format(tumorDictionary, normalDictionary))

    val mappedTumorReads = rawTumorReads.map(_.getMappedRead).filter(_.mdTag.isDefined)
    val mappedNormalReads = rawNormalReads.map(_.getMappedRead).filter(_.mdTag.isDefined)

    mappedTumorReads.persist()
    mappedNormalReads.persist()

    Common.progress("Loaded %,d tumor mapped non-duplicate MdTag-containing reads into %,d partitions.".format(
      mappedTumorReads.count, mappedTumorReads.partitions.length))
    Common.progress("Loaded %,d normal mapped non-duplicate MdTag-containing reads into %,d partitions.".format(
      mappedNormalReads.count, mappedNormalReads.partitions.length))

    val loci = Common.loci(args, normalDictionary)
    val lociPartitions = DistributedUtil.partitionLociAccordingToArgs(args, loci, mappedTumorReads, mappedNormalReads)
    val contigToNum = sc.broadcast(loci.contigs.sorted.zipWithIndex.toMap)
    val numToContig = sc.broadcast(contigToNum.value.map(pair => (pair._2, pair._1)))
    def labelLocus(contig: String, locus: Long): LocusLabel = {
      (contigToNum.value(contig), locus)
    }

    if (args.trainModelOutput.nonEmpty) {
      // Training.
      val labels = getLabelsFromArgs(args, loci)
      val broadcastLabels = sc.broadcast(labels)
      val features = getFeatures(args, lociPartitions, mappedTumorReads, mappedNormalReads, labelLocus)
      val labeledFeatures = features.map({
        case ((contigNum, locus), features) => {
          val contig = numToContig.value(contigNum.toInt)
          val label = broadcastLabels.value.onContig(contig).get(locus).get.toDouble
          LabeledPoint(label, features)
        }
      })
      val model = LogisticRegressionWithSGD.train(labeledFeatures, args.trainNumIterations)
      mappedTumorReads.unpersist()
      mappedNormalReads.unpersist()
      Common.progress("Done training model.")
      Common.progress("Model: %s".format(model.toString))

      // Write model
      writeModel(args, model)
    } else {
      // Calling.
      if (args.predictModelInput.isEmpty) {
        throw new IllegalArgumentException("Either -predict-model-input or -train-model-output must be specified")
      }

      val model = readModel(args)
      val dummyLabels = sc.broadcast(lociPartitions)
      val features = getFeatures(args, lociPartitions, mappedTumorReads, mappedNormalReads, labelLocus).sortByKey()
      val predictions = model.predict(features.map(pair => pair._2))
      val locusAndPredictions = features.map(pair => (pair._1)).zip(predictions)
      val called = locusAndPredictions.filter(pair => pair._2 > .5).map(_._1).collect

      val calledBuiler = LociSet.newBuilder
      called.foreach({
        case (contigNum, locus) => {
          calledBuiler.put(numToContig.value(contigNum.toInt), locus, locus + 1)
        }
      })
      val calledLoci = calledBuiler.result

      Common.progress("Called %,d loci.".format(calledLoci.count))

      println(calledLoci.truncatedString(1000))
    }
    DelayedMessages.default.print()
  }

  def writeModel(args: Arguments, model: LogisticRegressionModel) = {
    val filesystem = FileSystem.get(new Configuration())
    val path = new Path(args.trainModelOutput)
    val writer = new BufferedWriter(new OutputStreamWriter(filesystem.create(path, true)))
    writer.write("%f\n".format(model.intercept))
    model.weights.foreach(weight => writer.write("%f\n".format(weight)))
    writer.close()
    Common.progress("Wrote: %s".format(args.trainModelOutput))
  }

  def readModel(args: Arguments): LogisticRegressionModel = {
    Common.progress("Loading model.")
    val filesystem = FileSystem.get(new Configuration())
    val path = new Path(args.predictModelInput)
    val reader = new BufferedReader((new InputStreamReader(filesystem.open(path))))
    val intercept = reader.readLine().toDouble
    var line = reader.readLine()
    val weights = new ArrayBuffer[Double]
    while (line != null) {
      val weight = line.toDouble
      weights += weight
    }
    Common.progress("Loaded model with %,d weights.".format(weights.length))
    new LogisticRegressionModel(weights.toArray, intercept)
  }

  def getLabelsFromArgs(args: Arguments, allLoci: LociSet): LociMap[Long] = {
    Common.progress("Building training labels.")
    val calledLoci = LociSet.parse(args.trainLociCalled)
    val builder = LociMap.newBuilder[Long]
    builder.put(allLoci, 0)
    builder.put(calledLoci, 1)
    val result = builder.result
    Common.progress("Built training labels: %,d calls of %,d total loci".format(calledLoci.count, allLoci.count))
    result
  }

  def getFeatures(
    args: Arguments,
    lociPartitions: LociMap[Long],
    tumorReads: RDD[MappedRead],
    normalReads: RDD[MappedRead],
    labeler: (String, Long) => LocusLabel): RDD[(LocusLabel, Array[Double])] = {

    val pileupPoints: RDD[(LocusLabel, Array[Double])] = DistributedUtil.pileupFlatMapTwoRDDs[(LocusLabel, Array[Double])](
      tumorReads,
      normalReads,
      lociPartitions,
      (contig, locus, pileupTumor, pileupNormal) => {
        val locusLabel = labeler(contig, locus)
        val features = pileupFeatures(pileupTumor, pileupNormal)
        Iterator((locusLabel, features))
      })
    pileupPoints
  }

  def pileupFeatures(pileupTumor: Pileup, pileupNormal: Pileup): Array[Double] = {
    val result = new ArrayBuffer[Double]

    // Feature: Percent differences in evidence for each base
    val possibleAllelesTumor = SomaticThresholdVariantCaller.possibleSNVAllelePercents(pileupTumor)
    val possibleAllelesNormal = SomaticThresholdVariantCaller.possibleSNVAllelePercents(pileupNormal)
    val differences = Bases.standardBases.map(base => possibleAllelesTumor(base) - possibleAllelesNormal(base)).sorted
    result ++= differences

    result.toArray
  }

}