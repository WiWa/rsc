package rsc.cli

import java.io.{BufferedInputStream, ByteArrayOutputStream, File, InputStream}
import java.nio.file.{Files, Paths}
import rsc.output.Output
import rsc.report.ConsoleReporter
import scala.meta.internal.scalasig.ScalasigCodec
import scala.meta.scalasig.lowlevel.Scalasig

object SigToClass {

  def main(args: Array[String]): Unit = {
    val result = process(args)
    if (result) sys.exit(0) else sys.exit(1)
  }

  def process(args: Array[String]): Boolean = {
    val outputStr = args.head
    val settings = rsc.settings.Settings(d = Paths.get(outputStr))
    val reporter = ConsoleReporter(settings)
    val output = Output(settings)
    val paths = args(1).split(File.pathSeparatorChar).map(s => Paths.get(s))
    try {
      paths.foreach { path =>
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
          } finally stream.close()
        }

        val sigfilename = path.toString
        val name = sigfilename.stripSuffix(".sig")
        val ss: Scalasig = ScalasigCodec.fromBytes(name, sigfilename, bytes)
        val writer = rsc.scalasig.Writer(settings, reporter, null, output)
        writer.writeScalasig(ss)
      }
      true
    } finally {
      output.close()
    }
  }

}
