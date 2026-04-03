package nzea_core.backend.integer.nnu

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Paths}

/** Parses remu `mnist_infer` weight blobs: 12-byte prefix (scale at bytes 8–11 as LE [[Float]]),
  * then row-major signed int8 weights. Matches
  * `remu_simulator/.../OP_WJCUS0/mnist_infer.rs` `parse_weight_binary_const` + `scale_to_q16`
  * (Q16 = truncate toward zero, not `round`).
  *
  * Also materializes weight-only binaries under `build/nnu_mem_init/` for
  * [[chisel3.util.experimental.loadMemoryFromFile]] on [[chisel3.SyncReadMem]] (Vivado BRAM inference).
  */
object MnistRemuWeightBin {

  def nnuDir: java.nio.file.Path =
    Option(System.getenv("NNU_WEIGHT_DIR")) match {
      case Some(d) => Paths.get(d)
      case None =>
        Paths.get(
          System.getProperty("user.dir", "."),
          "nzea_core",
          "src",
          "backend",
          "integer",
          "nnu"
        )
    }

  private def readBin(rel: String): Array[Byte] = {
    val p = nnuDir.resolve(rel)
    if (!Files.exists(p))
      throw new IllegalStateException(
        s"MNIST weight bin missing: $p (copy fc{{1,2,3}}_weight.bin from remu OP_WJCUS0 or set NNU_WEIGHT_DIR)"
      )
    Files.readAllBytes(p)
  }

  /** @return (weight body bytes only, scale_q16 as int32) */
  private def parseLayerBytes(rows: Int, cols: Int, file: String): (Array[Byte], Int) = {
    val data  = readBin(file)
    val need  = 12 + rows * cols
    val got   = data.length
    require(got >= need, s"$file: need at least $need bytes, got $got")
    val scale = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat(8)
    val scaleQ = (scale * (1 << 16).toFloat).toInt
    val w = new Array[Byte](rows * cols)
    System.arraycopy(data, 12, w, 0, rows * cols)
    (w, scaleQ)
  }

  val (fc1WeightBytes, fc1ScaleQ16) =
    parseLayerBytes(NnuSramDims.Fc1Rows, NnuSramDims.Fc1Cols, "fc1_weight.bin")
  val (fc2WeightBytes, fc2ScaleQ16) =
    parseLayerBytes(NnuSramDims.Fc2Rows, NnuSramDims.Fc2Cols, "fc2_weight.bin")
  val (fc3WeightBytes, fc3ScaleQ16) =
    parseLayerBytes(NnuSramDims.Fc3Rows, NnuSramDims.Fc3Cols, "fc3_weight.bin")

  require(fc1WeightBytes.length == NnuSramDims.Fc1Depth, "fc1 weight count")
  require(fc2WeightBytes.length == NnuSramDims.Fc2Depth, "fc2 weight count")
  require(fc3WeightBytes.length == NnuSramDims.Fc3Depth, "fc3 weight count")

  /** Absolute paths to 8-bit weight-only images for [[loadMemoryFromFile]] (binary, one byte per entry). */
  def syncReadMemInitFilePaths: (String, String, String) = {
    val root = Paths.get(System.getProperty("user.dir", ".")).resolve("build").resolve("nnu_mem_init")
    Files.createDirectories(root)
    val f1 = root.resolve("fc1_w8.bin")
    val f2 = root.resolve("fc2_w8.bin")
    val f3 = root.resolve("fc3_w8.bin")
    Files.write(f1, fc1WeightBytes)
    Files.write(f2, fc2WeightBytes)
    Files.write(f3, fc3WeightBytes)
    (
      f1.toAbsolutePath.normalize().toString,
      f2.toAbsolutePath.normalize().toString,
      f3.toAbsolutePath.normalize().toString
    )
  }
}
