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
      if (binary.isSig) {
        val bytes = SigUtils.readBinaryBytes(binary)
        val (name, source) = SigUtils.getNameSource(binary)
        ParsedClassfile(binary, Classfile(name, source, ScalaPayload(bytes)))
      } else {
        val classfile = ClassfileCodec.fromBinary(binary)
        ParsedClassfile(binary, classfile)
      }
    } catch {
      case ex: Throwable =>
        FailedClassfile(binary, ClassfileReadException(binary, ex))
    }
  }
}
