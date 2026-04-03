package nzea_core.backend.integer.nnu

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Paths}

/** Elaboration-time loader for remu `mnist_infer` weight blobs: bytes [8..11] LE f32 scale, [12..] row-major i8. */
private[nnu] case class MnistLayerBlob(scaleQ16: Int, bytes: Array[Byte])

private[nnu] object MnistWeightLoader {

  /** FC1 256×784, FC2 128×256, FC3 10×128 — sizes match remu `mnist_infer.rs`. */
  val fc1: MnistLayerBlob = loadLayer("fc1_weight.bin", 256, 784)
  val fc2: MnistLayerBlob = loadLayer("fc2_weight.bin", 128, 256)
  val fc3: MnistLayerBlob = loadLayer("fc3_weight.bin", 10, 128)

  private def readAllBytes(name: String): Array[Byte] = {
    val cl  = Thread.currentThread().getContextClassLoader
    val res = Option(cl.getResourceAsStream(s"nnu/$name"))
    val fromRes = res.map { in =>
      try {
        val out = new java.io.ByteArrayOutputStream()
        val buf = new Array[Byte](16384)
        var n   = in.read(buf)
        while (n >= 0) {
          out.write(buf, 0, n)
          n = in.read(buf)
        }
        out.toByteArray
      } finally in.close()
    }
    fromRes.getOrElse {
      val p = Paths.get(
        System.getProperty("user.dir", "."),
        "nzea_core",
        "src",
        "backend",
        "integer",
        "nnu",
        name
      )
      if (Files.exists(p)) Files.readAllBytes(p)
      else
        throw new IllegalStateException(
          s"MNIST weight `$name` not found: place under nzea_core/src/main/resources/nnu/ or nzea_core/src/backend/integer/nnu/"
        )
    }
  }

  private def loadLayer(name: String, rows: Int, cols: Int): MnistLayerBlob = {
    val data = readAllBytes(name)
    val need = 12 + rows * cols
    require(
      data.length >= need,
      s"$name: need >= $need bytes, got ${data.length}"
    )
    val scale    = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat(8)
    val scaleQ16 = (scale.toDouble * (1 << 16).toDouble).toInt
    val w        = new Array[Byte](rows * cols)
    System.arraycopy(data, 12, w, 0, rows * cols)
    MnistLayerBlob(scaleQ16, w)
  }
}
