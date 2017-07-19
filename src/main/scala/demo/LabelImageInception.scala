/* Copyright 2016 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License")
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package demo

import geotrellis.raster.{Tile, MultibandTile}
import geotrellis.raster.io.geotiff.MultibandGeoTiff
import geotrellis.raster.io.geotiff.reader.GeoTiffReader
import org.tensorflow.{DataType, Graph, Output, Session, Tensor, TensorFlow, Shape}
import spray.json._
import DefaultJsonProtocol._

import java.io.{IOException, PrintStream}
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.file.{Files, Path, Paths}
import java.util.{Arrays, List}

/** Sample use of the TensorFlow Java API to label images using a pre-trained model. */
object LabelImage {
  def printUsage(s: PrintStream) {
    val url: String =
      "https://storage.googleapis.com/download.tensorflow.org/models/inception5h.zip"
    s.println(
      "Java program that uses a pre-trained Inception model (http://arxiv.org/abs/1512.00567)")
    s.println("to label Tiff images.")
    s.println("TensorFlow version: " + TensorFlow.version)
    s.println
    s.println("Usage: label_image <model dir> <image file>")
    s.println
    s.println("Where:")
    s.println("<model dir> is a directory containing the unzipped contents of the inception model")
    s.println("            (from " + url + ")")
    s.println("<image file> is the path to a JPEG image file")
  }

  def main(args: Array[String]) {
    if (args.length != 2) {
      printUsage(System.err)
      System.exit(1)
    }
    // TODO: set to env vars
    val rasterVisionNotebookDir = "/Users/yoninachmany/azavea/raster-vision-notebooks"
    val rasterVisionDataDir = "/Users/yoninachmany/azavea/raster-vision-data"
    val rasterVisionDatasetsDir = rasterVisionDataDir + "/" + "datasets"
    var rasterVisionPlanetKaggleDatasetDir = rasterVisionDatasetsDir + "/" + "planet_kaggle"
    val rasterVisionResultsDir = rasterVisionDataDir + "/" + "results"
    val experiment = "tagging/7_7_17/baseline_cyclic_1"
    val experimentDir = rasterVisionResultsDir + "/" + experiment

    val modelDir: String = args(0) //rasterVisionNotebookDir //args(0)
    val imageFile: String = args(1) //rasterVisionDataDir + "/" + "datasets/planet_kaggle/train-tif-v2/train_0.tif"// args(1)

    val startTime: Long = System.currentTimeMillis
    val graphDef: Array[Byte] = readAllBytesOrExit(Paths.get(modelDir, "tensorflow_inception_graph.pb"))
    // val graphDef: Array[Byte] = readAllBytesOrExit(Paths.get(modelDir, "output_graph.pb"))
    val labels: List[String] = readAllLinesOrExit(Paths.get(modelDir, "imagenet_comp_graph_label_strings.txt"))
    // val labels: List[String] = readAllLinesOrExit(Paths.get(modelDir, "labels.txt"))
    // val imageBytes: Array[Byte] = readAllBytesOrExit(Paths.get(imageFile))
    val imagePathString: String = Paths.get(imageFile).toString

    var image: Tensor = constructAndExecuteGraphToNormalizeImage(imagePathString)
    try {
      // image = constructAndExecuteGraphToNormalizeImage(imageBytes)
      // image = constructAndExecuteGraphToNormalizeImage(imagePathString)
      val labelProbabilities: Array[Float] = executeInceptionGraph(graphDef, image)
      val stopTime: Long = System.currentTimeMillis
      val elapsedTime: Long = stopTime - startTime
      println(f"ELAPSED TIME: $elapsedTime%d milliseconds")

      // val thresholdsPath: String = experimentDir + "/" + "thresholds.json"
      // val source: scala.io.Source = scala.io.Source.fromFile(thresholdsPath)
      // val lines: String = try source.mkString finally source.close
      // val thresholds: Array[Float] = lines.parseJson.convertTo[Array[Float]]
      // var i: Int = 0
      // for (i <- 0 to labels.size - 1) {
      //   val labelProbability: Float = labelProbabilities(i) * 100f
      //   val threshold: Float = thresholds(i) * 100f
      //   val label: String = labels.get(i)
      //   if (labelProbability >= threshold) {
      //     println(f"MATCH: $label%-20s ($i%d) $labelProbability%15.2f%% likely $threshold%15.2f%% threshold")
      //   }
      // }

      val bestLabelIdx: Int = maxIndex(labelProbabilities)
      val bestLabel: String = labels.get(bestLabelIdx)
      val bestLabelLikelihood: Float = labelProbabilities(bestLabelIdx) * 100f
      println(f"BEST MATCH: $bestLabel%s ($bestLabelLikelihood%.2f%% likely)")
    } finally {
      image.close
    }
  }

  private def constructAndExecuteGraphToNormalizeImage = true
  // def constructAndExecuteGraphToNormalizeImage(imageBytes: Array[Byte]): Tensor = {
  def constructAndExecuteGraphToNormalizeImage(imagePathString: String): Tensor = {
    var g: Graph = null

    try {
      g = new Graph
      val b: GraphBuilder = new GraphBuilder(g)
      // Some constants specific to the pre-trained model at:
      // https://storage.googleapis.com/download.tensorflow.org/models/inception5h.zip
      //
      // - The model was trained with images scaled to 224x224 pixels.
      // - The colors, represented as R, G, B in 1-byte each were converted to
      //   float using (value - Mean)/Scale.
      val H: Int = 224
      val W: Int = 224
      val mean: Float = 117f
      val scale: Float = 1f
      val statsPath: String = "/Users/yoninachmany/azavea/raster-vision-data/datasets/planet_kaggle/planet_kaggle_jpg_channel_stats.json"
      val source: scala.io.Source = scala.io.Source.fromFile(statsPath)
      val lines: String = try source.mkString finally source.close
      val stats: Map[String, Array[Float]] = lines.parseJson.convertTo[Map[String, Array[Float]]]
      val means: Array[Float] = stats("means")
      val stds: Array[Float] = stats("stds")

      // Since the graph is being constructed once per execution here, we can use a constant for the
      // input image. If the graph were to be re-used for multiple input images, a placeholder would
      // have been more appropriate.
      // val input: Output = b.constant("input", imageBytes)

      var imageTensor: Tensor = null
      var meansTensor: Tensor = null
      var stdsTensor: Tensor = null
      try {
        imageTensor = b.decodeTiff(imagePathString)

        val input: Output = b.constantTensor("input", imageTensor)
        // val input: Output = b.decodeJpeg(path, 3)

        // val shape: Array[Long] = imageTensor.shape
        // val height: Int = shape(0).asInstanceOf[Int]
        // val width: Int = shape(1).asInstanceOf[Int]
        // val channels: Int = shape(2).asInstanceOf[Int]
        // val meansArray: Array[Array[Array[Float]]] = Array.ofDim(height, width, channels)
        // val stdsArray: Array[Array[Array[Float]]] = Array.ofDim(height, width, channels)
        //
        // // build a matrix
        // for (h <- 0 to height - 1) {
        //    for (w <- 0 to width - 1) {
        //      for (c <- 0 to channels - 1) {
        //        meansArray(h)(w)(c) = means(c)
        //        stdsArray(h)(w)(c) = stds(c)
        //      }
        //    }
        // }
        //
        // meansTensor = Tensor.create(meansArray)
        // stdsTensor = Tensor.create(stdsArray)
        // val meansOutput: Output = b.constantTensor("means", meansTensor)
        // val stdsOutput: Output = b.constantTensor("stds", stdsTensor)

        // since division and subtraction cannot be done by axis, we need to unstack and then stack
        // after unstacking, subtract and divide respective mean and std, then stack
        // ! instead of looping, just divide and substract from appropriate tensor!!
        val output: Output =
          b.div(
            b.sub(
              b.resizeBilinear(
              b.expandDims(
                // b.cast(b.decodeJpeg(input, 3), DataType.FLOAT),
                b.cast(input, DataType.FLOAT),
                b.constant("make_batch", 0)),
              b.constant("size", Array[Int](H, W))),
            //   meansOutput),
            // stdsOutput)
              b.constant("mean", mean)),
            b.constant("scale", scale))

          var s: Session = null
          try {
            s = new Session(g)
            return s.runner.fetch(output.op.name).run.get(0)
          } finally {
            s.close
          }
      } finally {
        // imageTensor.close
        // meansTensor.close
        // stdsTensor.close
      }
    } finally {
      g.close
    }
  }

  private def executeInceptionGraph = true
  def executeInceptionGraph(graphDef: Array[Byte], image: Tensor): Array[Float] = {
    var g: Graph = null
    try {
      g = new Graph
      g.importGraphDef(graphDef)
      var s: Session = null
      var result: Tensor = null
      try {
        s = new Session(g)
        // result = s.runner.feed("input_1", image).fetch("dense/Sigmoid").run.get(0)
        result = s.runner().feed("input", image).fetch("output").run().get(0)
        val rshape: Array[Long] = result.shape
        val rshapeString: String = Arrays.toString(rshape)
        if (result.numDimensions != 2 || rshape(0) != 1) {
          throw new RuntimeException(
            String.format(
              f"Expected model to produce a [1 N] shaped tensor where N is the number of labels, instead it produced one with shape $rshapeString%s"))
        }
        val nlabels: Int = rshape(1).asInstanceOf[Int]
        return result.copyTo(Array.ofDim[Float](1, nlabels))(0)
      } finally {
        s.close
      }
    } finally {
      g.close
    }
  }

  private def maxIndex = true
  def maxIndex(probabilities: Array[Float]): Int = {
    var best: Int = 0
    val i: Int = 1
    for (i <- 1 to probabilities.length-1) {
      if (probabilities(i) > probabilities(best)) {
        best = i
      }
    }
    return best
  }

  private def readAllBytesOrExit = true
  def readAllBytesOrExit(path: Path): Array[Byte] = {
    try {
      return Files.readAllBytes(path)
    } catch {
      case e: IOException => {
        System.err.println("Failed to read [" + path + "]: " + e.getMessage)
        System.exit(1)
      }
    }
    return null
  }

  private def readAllLinesOrExit = true
  def readAllLinesOrExit(path: Path): List[String] = {
    try {
      return Files.readAllLines(path, Charset.forName("UTF-8"))
    } catch {
      case e: IOException => {
        System.err.println("Failed to read [" + path + "]: " + e.getMessage)
        System.exit(0)
      }
    }
    return null
  }

  // In the fullness of time, equivalents of the methods of this class should be auto-generated from
  // the OpDefs linked into libtensorflow_jni.so. That would match what is done in other languages
  // like Python, C++ and Go.
  class GraphBuilder(g: Graph) {
    def div(x: Output, y: Output): Output = {
      return binaryOp("Div", x, y)
    }

    def sub(x: Output, y: Output): Output = {
      return binaryOp("Sub", x, y)
    }

    def resizeBilinear(images: Output, size: Output): Output = {
      return binaryOp("ResizeBilinear", images, size)
    }

    def expandDims(input: Output, dim: Output): Output = {
      return binaryOp("ExpandDims", input, dim)
    }

    def cast(value: Output, dtype: DataType): Output = {
      return g.opBuilder("Cast", "Cast").addInput(value).setAttr("DstT", dtype).build.output(0)
    }

    // def decodeJpeg(contents: Output, channels: Long): Output = {
    //   return g.opBuilder("DecodeJpeg", "DecodeJpeg")
    //     .addInput(contents)
    //     .setAttr("channels", channels)
    //     .build
    //     .output(0)
    // }

    /**
     * Decode a TIFF-encoded image to a uint8 tensor using GeoTrellis.
     * TensorFlow Images: https://www.tensorflow.org/api_guides/python/image.
     * DecodeJpeg: https://www.tensorflow.org/api_docs/cc/class/tensorflow/ops/decode-jpeg.
     */
    def decodeTiff(imagePathString: String): Tensor = {
      // Read GeoTiff: https://geotrellis.readthedocs.io/en/latest/tutorials/reading-geoTiffs.html
      val tile: MultibandTile = GeoTiffReader.readMultiband(imagePathString).tile

      // Testing documentation:
      // https://github.com/loretoparisi/tensorflow-java
      // example-400x288.jpg -> BEST MATCH: lakeside (19.00% likely)

      // Original Approach: Tensor.create(Object o)
      // https://www.tensorflow.org/api_docs/java/reference/org/tensorflow/Tensor.html#create(java.lang.Object)
      // example-400x288.jpg.tif -> BEST MATCH: lakeside (18.52% likely)
      // int height = tile.rows
      // int width = tile.cols
      // int channels = tile.bandCount
      // int[][][] int3DArray = new int[height][width][channels]
      // for (int h = 0 h < height h++) {
      //   for (int w = 0 w < width w++) {
      //     for (int c = 0 c < channels c++) {
      //       int3DArray[h][w][c] = tile.band(c).get(w, h)
      //     }
      //   }
      // }
      //
      // Tensor imageTensor = Tensor.create(int3DArray)
      // System.out.println(imageTensor.dataType) // decodeJpeg returns uint8 tensor
      // DataType.INT32
      // Reason: dataTypeOf(Object o) -> int instances -> DataType.INT32
      // https://github.com/tensorflow/tensorflow/blob/master/tensorflow/java/src/main/java/org/tensorflow/Tensor.java#L531-L532
      // return imageTensor

      // Only other Approach: Tensor.create(DataType dataType, long[] shape, ByteBuffer data)
      // https://www.tensorflow.org/api_docs/java/reference/org/tensorflow/Tensor.html#create(org.tensorflow.DataType, long[], java.nio.ByteBuffer)
      val dataType: DataType = DataType.UINT8
      val height: Int = tile.rows
      val width: Int = tile.cols
      // TODO: HANDLE 4 channels!!
      val channels: Int = 3 //tile.bandCount
      val shape: Array[Long] = Array(height.asInstanceOf[Long], width.asInstanceOf[Long], channels.asInstanceOf[Long])
      val byteArray: Array[Byte] = new Array(height * width * channels)

      // How should byteArray be populated?
      // Intentionally wrong: non-interleaved, i.e. all R, all G, all B
      // BEST MATCH: handkerchief (52.15% likely)
      // int bandSize = height * width
      // for (int i = 0 i < channels i++) {
      //   System.arraycopy(tile.band(i).toBytes, 0, byteArray, i*bandSize, bandSize)
      // }

      // Hopefully right: interleaved R, G, B, by height then width
      // BEST MATCH: lakeside (18.52% likely)
      var h: Int = 0
      var w: Int = 0
      var c: Int = 0
      for (h <- 0 to height - 1) {
        for (w <- 0 to width - 1) {
          for (c <- 0 to channels - 1) {
            byteArray(h * (width * channels) + w * channels + c) = (tile.band(c).get(w, h)).asInstanceOf[Byte]
          }
        }
      }

      val data: ByteBuffer = ByteBuffer.wrap(byteArray)
      val imageTensor: Tensor = Tensor.create(dataType, shape, data)
      // System.out.println(imageTensor.dataType) // decodeJpeg returns uint8 tensor
      // DataType.UINT8
      return imageTensor
    }

    def constant(name: String, value: Any): Output = {
      val t: Tensor = Tensor.create(value)
      val o: Output = g.opBuilder("Const", name)
        .setAttr("dtype", t.dataType)
        .setAttr("value", t)
        .build
        .output(0)
      t.close
      return o
    }

    def constantTensor(name: String, t: Tensor): Output = {
      return g.opBuilder("Const", name)
        .setAttr("dtype", t.dataType)
        .setAttr("value", t)
        .build
        .output(0)
    }

    private def binaryOp = true
    def binaryOp(ty: String, in1: Output, in2: Output): Output = {
      return g.opBuilder(ty, ty).addInput(in1).addInput(in2).build.output(0)
    }

    def stack(values: Output, axis: Long): Output = {
      return g.opBuilder("Stack", "Stack").addInput(values).setAttr("axis", axis).build.output(0)
    }

    def unstack(value: Output, axis: Long): Output = {
      return g.opBuilder("Unstack", "Unstack").addInput(value).setAttr("axis", axis).build.output(0)
    }
  }
}