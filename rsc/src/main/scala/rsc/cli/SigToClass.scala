package rsc.cli

import java.io.{BufferedInputStream, ByteArrayOutputStream, File, InputStream}
import java.nio.file.{Files, Paths}
import rsc.output.Output
import rsc.report.ConsoleReporter
import scala.meta.internal.scalasig.ScalasigCodec
import scala.meta.scalasig.{Binaries, PathBinary}
import scala.meta.scalasig.lowlevel.{ParsedScalasig, Scalasig, ScalasigResult}

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
    val paths = args(1).split(File.pathSeparatorChar).map(s => Paths.get(s)).toList
    try {
      Binaries.apply(paths) { binary =>
        val ParsedScalasig(_, _, ss) = Scalasig.fromBinary(binary)
        val writer = rsc.scalasig.Writer(settings, reporter, null, output)
        writer.writeScalasig(ss)
      }
      true
    } finally {
      output.close()
    }
  }

}
