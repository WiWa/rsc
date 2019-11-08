// Copyright (c) 2017-2019 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package scala.meta.scalasig

import java.io.ByteArrayOutputStream
import scala.meta.internal.scalasig._

case class Classfile(name: String, source: String, payload: Payload) {
  def toBinary: Array[Byte] = {
    try {
      ClassfileCodec.toBinary(this)
    } catch {
      case ex: Throwable =>
        throw ClassfileWriteException(this, ex)
    }
  }
}

object Classfile {
  def fromBinary(binary: Binary): ClassfileResult = {
    try {
      binary match {
        case b@PathBinary(path) if path.toString.endsWith(".sig") =>
          val pathStr = b.path.toString
          val stream = b.openStream()
          val bytes = try {
            val baos = new ByteArrayOutputStream()
            val buf = new Array[Byte](1024)
            var len = stream.read(buf)
            while (len != -1) {
              baos.write(buf, 0, len)
              len = stream.read(buf)
            }
            baos.toByteArray
          } finally stream.close()

          ParsedClassfile(binary, Classfile(
            pathStr.stripSuffix(".sig"),
            pathStr,
            ScalaPayload(bytes)
          ))
        case _ =>
          val classfile = ClassfileCodec.fromBinary(binary)
          ParsedClassfile(binary, classfile)
      }
    } catch {
      case ex: Throwable =>
        FailedClassfile(binary, ClassfileReadException(binary, ex))
    }
  }
}
