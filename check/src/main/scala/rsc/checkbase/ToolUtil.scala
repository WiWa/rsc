// Copyright (c) 2017-2018 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package rsc.checkbase

import java.io._
import java.io.File.pathSeparator
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file._
import scala.collection.JavaConverters._
import scala.meta.cli._
import scala.meta.io._
import scala.util._

trait ToolUtil extends CacheUtil with NscUtil {
  def metacp(dependencyClasspath: List[Path], classpath: List[Path]): ToolResult[List[Path]] = {
    withConsole { console =>
      import scala.meta.metacp._
      val relative = Paths.get(metacpVersion).resolve("out")
      val fingerprint = Fingerprint(dependencyClasspath ++ classpath)
      val out = cacheDir("metacp", fingerprint).resolve(relative)
      if (Files.exists(out)) {
        Right(Files.list(out).iterator.asScala.toList)
      } else {
        val metaDp = Classpath(dependencyClasspath.map(AbsolutePath.apply))
        val metaCp = Classpath(classpath.map(AbsolutePath.apply))
        val metaOut = AbsolutePath(out)
        val settings = Settings()
          .withDependencyClasspath(metaDp)
          .withClasspath(metaCp)
          .withScalaLibrarySynthetics(true)
          .withOut(metaOut)
        val result = Metacp.process(settings, console.reporter)
        result.classpath match {
          case Some(classpath) => Right(classpath.entries.map(_.toNIO))
          case None => Left(List(console.err))
        }
      }
    }
  }

  def mjar(classpath: List[Path]): ToolResult[Path] = {
    withConsole { console =>
      import scala.meta.mjar._
      val out = Files.createTempFile("out", ".jar")
      val settings = Settings().withClasspath(classpath).withOut(out)
      Mjar.process(settings, console.reporter) match {
        case Some(out) => Right(out)
        case None => Left(List(console.err))
      }
    }
  }

  def rsci(classpath: List[Path]): ToolResult[List[Path]] = {
    metacp(Nil, classpath).right.flatMap { metacpClasspath =>
      var success = true
      val errors = List.newBuilder[String]
      metacpClasspath.foreach { entry =>
        val relative = Paths.get(metaiVersion).resolve("done")
        val fingerprint = Fingerprint(entry)
        val done = cacheDir("metai", fingerprint).resolve(relative)
        if (Files.exists(done)) {
          ()
        } else {
          withConsole { console =>
            import scala.meta.metai._
            val metaiClasspath = Classpath(AbsolutePath(entry))
            val settings = Settings().withClasspath(metaiClasspath)
            val result = Metai.process(settings, console.reporter)
            success &= result.isSuccess
            if (console.err.nonEmpty) errors += console.err
          }
          Files.createDirectories(done.getParent)
          Files.createFile(done)
        }
      }
      if (success) Right(metacpClasspath)
      else Left(errors.result)
    }
  }

  def rsc(classpath: List[Path], sources: List[Path]): ToolResult[Path] = {
    import _root_.rsc.Compiler
    import _root_.rsc.report._
    import _root_.rsc.settings._
    val semanticdbDir = Files.createTempDirectory("rsc-semanticdb_")
    rsci(classpath).right.flatMap { rscClasspath =>
      val out = semanticdbDir.resolve("META-INF/semanticdb/rsc.semanticdb")
      val settings = Settings(cp = rscClasspath, ins = sources, out = out)
      val reporter = StoreReporter(settings)
      val compiler = Compiler(settings, reporter)
      try {
        compiler.run()
        if (reporter.problems.isEmpty) {
          Right(semanticdbDir)
        } else {
          Left(reporter.problems.map(_.str))
        }
      } finally {
        compiler.close()
      }
    }
  }

  def scalac(classpath: List[Path], sources: List[Path]): ToolResult[Path] = {
    withConsole { console =>
      val fingerprint = Fingerprint(classpath ++ sources)
      val out = cacheDir("scalac", fingerprint).resolve("nsc.jar")
      if (Files.exists(out)) {
        Right(out)
      } else {
        import scala.tools.nsc.{classpath => _, _}
        import scala.tools.nsc.reporters._
        val settings = new Settings
        settings.outdir.value = out.toString
        settings.classpath.value = classpath.mkString(pathSeparator)
        val reporter = new StoreReporter
        val global = Global(settings, reporter)
        val run = new global.Run
        run.compile(sources.map(_.toString))
        if (reporter.hasErrors) {
          Left(reporter.infos.map(_.str).toList)
        } else {
          Right(out)
        }
      }
    }
  }

  private def metacpVersion: String = {
    scala.meta.internal.metacp.BuildInfo.version
  }

  private def metaiVersion: String = {
    metacpVersion
  }

  private def withConsole[T](fn: Console => T): T = {
    fn(new Console)
  }

  private class Console {
    private val baos = new ByteArrayOutputStream()
    private val ps = new PrintStream(baos)
    val reporter = Reporter().withSilentOut().withErr(ps)
    def err = new String(baos.toByteArray, UTF_8)
  }
}
