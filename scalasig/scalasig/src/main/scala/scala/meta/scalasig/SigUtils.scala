package scala.meta.scalasig

import java.io.ByteArrayOutputStream

object SigUtils {

  def readBinaryBytes(binary: Binary): Array[Byte] = {

    val stream = binary.openStream()

    try {
      val baos = new ByteArrayOutputStream()
      val buf = new Array[Byte](1024)
      var len = stream.read(buf)
      while (len != -1) {
        baos.write(buf, 0, len)
        len = stream.read(buf)
      }
      baos.toByteArray
    } finally {
      stream.close()
    }
  }

  def getNameSource(binary: Binary): (String, String) = {
    binary match {
      case b: UriBinary =>
        val uriStr = b.uri.toString
        if (uriStr.startsWith("jar:file:")) {
          val jar_sig = uriStr
            .stripPrefix("jar:file:")
            .split("!/")

          val jarFile = jar_sig.head
          val sigFile = jar_sig(1)
          (sigFile.stripSuffix(".sig"), jarFile)
        } else {
          throw new RuntimeException(s"UriBinary ending in .sig was not jar: $b")
        }
      case b: PathBinary =>
        val pathString = b.path.toString
        (pathString.stripSuffix(".sig"), pathString)
    }
  }
}
