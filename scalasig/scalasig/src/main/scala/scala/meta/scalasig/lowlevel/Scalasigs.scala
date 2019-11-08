// Copyright (c) 2017-2019 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package scala.meta.scalasig.lowlevel

import java.io.{BufferedInputStream, ByteArrayOutputStream, InputStream}
import java.nio.file._
import scala.meta.internal.scalasig.ScalasigCodec
import scala.meta.scalasig._

object Scalasigs {
  def apply(paths: List[Path])(fn: ScalasigResult => Unit): Unit = {
    paths.foreach(path => apply(path)(fn))
  }

  def apply(path: Path)(fn: ScalasigResult => Unit): Unit = {
    if (path.toString.endsWith(".sig")) {
      val bytes = {
        val stream: InputStream = new BufferedInputStream(Files.newInputStream(path))
        try {
          val baos = new ByteArrayOutputStream()
          val buf = new Array[Byte](1024)
          var len = stream.read(buf)
          while (len != -1) {
            baos.write(buf, 0, len)
            len = stream.read(buf)
          }
          baos.toByteArray
        }
        finally stream.close()
      }
      val ss = ScalasigCodec.fromBytes(path.toString, "", bytes)
      val pss = ParsedScalasig(
        PathBinary(path),
        Classfile(path.toString, path.toString, ScalaPayload(bytes)),
        ss
      )
      fn(pss)
    } else {
      Binaries(path)(binary => fn(Scalasig.fromBinary(binary)))
    }
  }
}
