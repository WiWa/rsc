// Copyright (c) 2017-2019 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package rsc.classpath

import java.nio.file._
import java.nio.file.attribute._
import java.util.HashMap
import java.util.concurrent.{BlockingQueue, ConcurrentHashMap, LinkedBlockingQueue, ThreadPoolExecutor}
import java.util.jar._
import rsc.util._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class Index private (
  entries: ConcurrentHashMap[Locator, Entry],
  implicit private val _ec: ExecutionContext
) extends AutoCloseable {

  def contains(loc: Locator): Boolean = {
    if (entries.containsKey(loc)) true
    else this.synchronized { entries.containsKey(loc) }
  }

  def get(loc: Locator): Entry = {
    val entry = entries.get(loc)
    if (entry != null) entry
    else this.synchronized {
      entries.get(loc)
    }
  }

  def apply(loc: Locator): Entry = {
    val entry = entries.get(loc)
    if (entry != null) entry
    else this.synchronized {
      val entry = entries.get(loc)
      if (entry != null) entry
      else crash(loc)
    }
  }

  def close(): Unit = this.synchronized {
    entries.values.iterator.asScala.foreach {
      case CompressedEntry(jar, _) => jar.close()
      case _ => ()
    }
  }

  def visit(root: Path): Unit = {
    if (Files.exists(root)) {
      if (Files.isDirectory(root)) {
        Files.walkFileTree(
          root,
          new SimpleFileVisitor[Path] {
            override def visitFile(
              file: Path,
              attrs: BasicFileAttributes
            ): FileVisitResult = {
              if (file.toString.endsWith(".class")) {
                val loc = root.relativize(file).toString
                entries.put(loc, UncompressedEntry(file))
              }
              super.visitFile(file, attrs)
            }
            override def preVisitDirectory(
              dir: Path,
              attrs: BasicFileAttributes
            ): FileVisitResult = {
              if (dir.endsWith("META-INF")) {
                FileVisitResult.SKIP_SUBTREE
              } else {
                if (dir != root) {
                  val loc = root.relativize(dir).toString + "/"
                  entries.put(loc, PackageEntry())
                }
                super.preVisitDirectory(dir, attrs)
              }
            }
          }
        )
      } else if (root.toString.endsWith(".jar")) {
        val jar = new JarFile(root.toFile)
        val jarEntries = jar.entries()
        val futures = mutable.ListBuffer.empty[Future[Unit]]
        while (jarEntries.hasMoreElements) {
          val jarEntry = jarEntries.nextElement()
          val f = Future {
            if (jarEntry.getName.endsWith(".class") && !jarEntry.getName.startsWith("META-INF")) {
              val loc = jarEntry.getName
              entries.put(loc, CompressedEntry(jar, jarEntry))
              val parts = jarEntry.getName.split("/").toList.dropRight(1)
              val packages = parts.inits.toList.dropRight(1).map(parts => parts.mkString("/") + "/")
              packages.foreach(entries.put(_, PackageEntry()))
            }
          }
        }
        Await.result(Future.sequence(futures), 2.seconds)
        val manifest = jar.getManifest
        if (manifest != null) {
          val classpathAttr = manifest.getMainAttributes.getValue("Class-Path")
          if (classpathAttr != null) {
            classpathAttr.split(" ").foreach { relativePath =>
              val parentPath = root.toAbsolutePath.getParent
              visit(parentPath.resolve(relativePath))
            }
          }
        }
      } else {
        ()
      }
    } else {
      ()
    }
  }
  def go(paths: List[Path]): Unit = {
    Await.result(Future.sequence(paths.map(p => Future {visit(p)})), 2.seconds)
  }
}
// Index in build tool?
object Index {
  def apply(
    entries: ConcurrentHashMap[Locator, Entry],
    _ec: ExecutionContext
  ): Index = {
    new Index(entries, _ec)
  }
}
