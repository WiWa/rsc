// Copyright (c) 2017-2019 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package scala.meta.scalasig

import java.io._
import java.net._
import java.nio.file._
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import scala.language.implicitConversions

sealed trait Binary {
  def openStream(): InputStream

  def isSig: Boolean
}

object Binary {
  implicit def uriBinary(uri: URI): Binary = UriBinary(uri)
  implicit def pathBinary(path: Path): Binary = PathBinary(path)
  implicit def bytesBinary(bytes: Array[Byte]): Binary = BytesBinary("<bytes>", bytes)
}

case object NoBinary extends Binary {
  def openStream(): InputStream = throw new UnsupportedOperationException()
  def isSig: Boolean = false
  override def toString: String = "<none>"
}

case class UriBinary(uri: URI) extends Binary {
  def openStream(): InputStream = uri.toURL.openStream()
  def isSig: Boolean = uri.toString.endsWith(".sig")
  override def toString: String = uri.toURL.toString
}
object UriBinary {
  def apply(jarfile: JarFile, entry: ZipEntry): UriBinary = {
    val uri = new URL("jar:file:" + jarfile.getName + "!/" + entry.getName).toURI
    new UriBinary(uri)
  }
}

case class PathBinary(path: Path) extends Binary {
  def openStream(): InputStream = Files.newInputStream(path)
  def isSig: Boolean = path.toString.endsWith(".sig")
  override def toString: String = path.toString
}

case class BytesBinary(label: String, bytes: Array[Byte]) extends Binary {
  def openStream(): InputStream = new ByteArrayInputStream(bytes)
  def isSig: Boolean = false
  override def toString: String = label
}
