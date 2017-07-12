/* Copyright 2016 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package demo;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import org.tensorflow.DataType;
import org.tensorflow.Graph;
import org.tensorflow.Output;
import org.tensorflow.Session;
import org.tensorflow.Tensor;
import org.tensorflow.TensorFlow;
import org.tensorflow.Shape;
import geotrellis.raster.io.geotiff.reader.GeoTiffReader;
import geotrellis.raster.io.geotiff.*;
import geotrellis.raster.MultibandTile;
import geotrellis.raster.Tile;

/** Sample use of the TensorFlow Java API to label images using a pre-trained model. */
public class LabelImage {
  private static void printUsage(PrintStream s) {
    final String url =
        "https://storage.googleapis.com/download.tensorflow.org/models/inception5h.zip";
    s.println(
        "Java program that uses a pre-trained Inception model (http://arxiv.org/abs/1512.00567)");
    s.println("to label Tiff images.");
    s.println("TensorFlow version: " + TensorFlow.version());
    s.println();
    s.println("Usage: label_image <model dir> <image file>");
    s.println();
    s.println("Where:");
    s.println("<model dir> is a directory containing the unzipped contents of the inception model");
    s.println("            (from " + url + ")");
    s.println("<image file> is the path to a JPEG image file");
  }

  public static void main(String[] args) {
    if (args.length != 2) {
      printUsage(System.err);
      System.exit(1);
    }
    String modelDir = args[0];
    String imageFile = args[1];

    long startTime = System.currentTimeMillis();
    byte[] graphDef = readAllBytesOrExit(Paths.get(modelDir, "tensorflow_inception_graph.pb"));
    List<String> labels =
        readAllLinesOrExit(Paths.get(modelDir, "imagenet_comp_graph_label_strings.txt"));

    String imagePathString = Paths.get(imageFile).toString();

    try (Tensor image = constructAndExecuteGraphToNormalizeImage(imagePathString)) {
      float[] labelProbabilities = executeInceptionGraph(graphDef, image);
      int bestLabelIdx = maxIndex(labelProbabilities);
      System.out.println(
          String.format(
              "BEST MATCH: %s (%.2f%% likely)",
              labels.get(bestLabelIdx), labelProbabilities[bestLabelIdx] * 100f));
    }
    long stopTime = System.currentTimeMillis();
    long elapsedTime = stopTime - startTime;
    System.out.println(elapsedTime);
  }

  private static Tensor constructAndExecuteGraphToNormalizeImage(String imagePathString) {
    try (Graph g = new Graph()) {
      GraphBuilder b = new GraphBuilder(g);
      // Some constants specific to the pre-trained model at:
      // https://storage.googleapis.com/download.tensorflow.org/models/inception5h.zip
      //
      // - The model was trained with images scaled to 224x224 pixels.
      // - The colors, represented as R, G, B in 1-byte each were converted to
      //   float using (value - Mean)/Scale.
      final int H = 224;
      final int W = 224;
      final float mean = 117f;
      final float scale = 1f;

      // Since the graph is being constructed once per execution here, we can use a constant for the
      // input image. If the graph were to be re-used for multiple input images, a placeholder would
      // have been more appropriate.
      Tensor imageTensor = b.decodeTiff(imagePathString);

      final Output input = b.constantTensor("input", imageTensor);
      final Output output =
          b.div(
              b.sub(
                  b.resizeBilinear(
                      b.expandDims(
                          b.cast(input, DataType.FLOAT),
                          b.constant("make_batch", 0)),
                      b.constant("size", new int[] {H, W})),
                  b.constant("mean", mean)),
              b.constant("scale", scale));
      try (Session s = new Session(g)) {
        return s.runner().fetch(output.op().name()).run().get(0);
      }
    }
  }

  private static float[] executeInceptionGraph(byte[] graphDef, Tensor image) {
    try (Graph g = new Graph()) {
      g.importGraphDef(graphDef);
      try (Session s = new Session(g);
          Tensor result = s.runner().feed("input", image).fetch("output").run().get(0)) {
        final long[] rshape = result.shape();
        if (result.numDimensions() != 2 || rshape[0] != 1) {
          throw new RuntimeException(
              String.format(
                  "Expected model to produce a [1 N] shaped tensor where N is the number of labels, instead it produced one with shape %s",
                  Arrays.toString(rshape)));
        }
        int nlabels = (int) rshape[1];
        return result.copyTo(new float[1][nlabels])[0];
      }
    }
  }

  private static int maxIndex(float[] probabilities) {
    int best = 0;
    for (int i = 1; i < probabilities.length; ++i) {
      if (probabilities[i] > probabilities[best]) {
        best = i;
        System.out.println(probabilities[best]);
      }
    }
    return best;
  }

  private static byte[] readAllBytesOrExit(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      System.err.println("Failed to read [" + path + "]: " + e.getMessage());
      System.exit(1);
    }
    return null;
  }

  private static List<String> readAllLinesOrExit(Path path) {
    try {
      return Files.readAllLines(path, Charset.forName("UTF-8"));
    } catch (IOException e) {
      System.err.println("Failed to read [" + path + "]: " + e.getMessage());
      System.exit(0);
    }
    return null;
  }

  // In the fullness of time, equivalents of the methods of this class should be auto-generated from
  // the OpDefs linked into libtensorflow_jni.so. That would match what is done in other languages
  // like Python, C++ and Go.
  static class GraphBuilder {
    GraphBuilder(Graph g) {
      this.g = g;
    }

    Output div(Output x, Output y) {
      return binaryOp("Div", x, y);
    }

    Output sub(Output x, Output y) {
      return binaryOp("Sub", x, y);
    }

    Output resizeBilinear(Output images, Output size) {
      return binaryOp("ResizeBilinear", images, size);
    }

    Output expandDims(Output input, Output dim) {
      return binaryOp("ExpandDims", input, dim);
    }

    Output cast(Output value, DataType dtype) {
      return g.opBuilder("Cast", "Cast").addInput(value).setAttr("DstT", dtype).build().output(0);
    }

    Output decodeJpeg(Output contents, long channels) {
      return g.opBuilder("DecodeJpeg", "DecodeJpeg")
          .addInput(contents)
          .setAttr("channels", channels)
          .build()
          .output(0);
    }

    /**
     * Decode a TIFF-encoded image to a uint8 tensor using GeoTrellis.
     * TensorFlow Images: https://www.tensorflow.org/api_guides/python/image.
     * DecodeJpeg: https://www.tensorflow.org/api_docs/cc/class/tensorflow/ops/decode-jpeg.
     */
    Tensor decodeTiff(String imagePathString) {
      // Read GeoTiff: https://geotrellis.readthedocs.io/en/latest/tutorials/reading-geoTiffs.html
      MultibandTile tile = GeoTiffReader.readMultiband(imagePathString).tile();

      // // Testing documentation:
      // // https://github.com/loretoparisi/tensorflow-java
      // // example-400x288.jpg -> BEST MATCH: lakeside (19.00% likely)
      //
      // // Original Approach: Tensor.create(Object o)
      // // https://www.tensorflow.org/api_docs/java/reference/org/tensorflow/Tensor.html#create(java.lang.Object)
      // // example-400x288.jpg.tif -> BEST MATCH: lakeside (18.52% likely)
      // int height = tile.rows();
      // int width = tile.cols();
      // int channels = tile.bandCount();
      // int[][][] int3DArray = new int[height][width][channels];
      // for (int h = 0; h < height; h++) {
      //   for (int w = 0; w < width; w++) {
      //     for (int c = 0; c < channels; c++) {
      //       int3DArray[h][w][c] = tile.band(c).get(w, h);
      //     }
      //   }
      // }
      //
      // Tensor imageTensor = Tensor.create(int3DArray);
      // // System.out.println(imageTensor.dataType()); // decodeJpeg returns uint8 tensor
      // // DataType.INT32
      // // Reason: dataTypeOf(Object o) -> int instances -> DataType.INT32
      // // https://github.com/tensorflow/tensorflow/blob/master/tensorflow/java/src/main/java/org/tensorflow/Tensor.java#L531-L532
      // return imageTensor;

      // Only other Approach: Tensor.create(DataType dataType, long[] shape, ByteBuffer data)
      // https://www.tensorflow.org/api_docs/java/reference/org/tensorflow/Tensor.html#create(org.tensorflow.DataType, long[], java.nio.ByteBuffer)
      DataType dataType = DataType.UINT8;
      int height = tile.rows();
      int width = tile.cols();
      int channels = tile.bandCount();
      long[] shape = {(long) height, (long) width, (long) channels};
      byte[] byteArray = new byte[height * width * channels];

      // How should byteArray be populated?
      // Intentionally wrong: non-interleaved, i.e. all R, all G, all B
      // BEST MATCH: handkerchief (52.15% likely)
      // int bandSize = height * width;
      // for (int i = 0; i < channels; i++) {
      //   System.arraycopy(tile.band(i).toBytes(), 0, byteArray, i*bandSize, bandSize);
      // }

      // Hopefully right: interleaved R, G, B, by height then width
      // BEST MATCH: lakeside (18.52% likely)
      for (int h = 0; h < height; h++) {
        for (int w = 0; w < width; w++) {
          for (int c = 0; c < channels; c++) {
            byteArray[h*(width*channels) + w*channels + c] = (byte) (tile.band(c).get(w, h));
          }
        }
      }

      ByteBuffer data = ByteBuffer.wrap(byteArray);
      Tensor imageTensor = Tensor.create(dataType, shape, data);
      // System.out.println(imageTensor.dataType()); // decodeJpeg returns uint8 tensor
      // DataType.UINT8
      return imageTensor;
    }

    Output constant(String name, Object value) {
      try (Tensor t = Tensor.create(value)) {
        return g.opBuilder("Const", name)
            .setAttr("dtype", t.dataType())
            .setAttr("value", t)
            .build()
            .output(0);
      }
    }

    Output constantTensor(String name, Tensor t) {
      return g.opBuilder("Const", name)
          .setAttr("dtype", t.dataType())
          .setAttr("value", t)
          .build()
          .output(0);
    }

    private Output binaryOp(String type, Output in1, Output in2) {
      return g.opBuilder(type, type).addInput(in1).addInput(in2).build().output(0);
    }

    private Graph g;
  }
}
