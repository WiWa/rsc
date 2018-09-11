// Copyright (c) 2017-2018 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package rsc

import java.io._
import java.nio.file._
import java.util.LinkedList
import rsc.gensym._
import rsc.inputs._
import rsc.lexis._
import rsc.outline._
import rsc.parse._
import rsc.pretty._
import rsc.report._
import rsc.scan._
import rsc.semanticdb._
import rsc.settings._
import rsc.syntax._
import rsc.util._

class Compiler(val settings: Settings, val reporter: Reporter) extends Closeable with Pretty {
  var trees: List[Source] = Nil
  var gensyms: Gensyms = Gensyms()
  var symtab: Symtab = Symtab(settings)
  var todo: Todo = Todo()

  def run(): Unit = {
    for ((taskName, taskFn) <- tasks) {
      val start = System.nanoTime()
      try {
        taskFn()
      } catch {
        case ex: Throwable =>
          reporter.append(CrashMessage(ex))
      }
      val end = System.nanoTime()
      val ms = (end - start) / 1000000
      if (settings.xprint("timings")) {
        reporter.append(VerboseMessage(s"Finished $taskName in $ms ms"))
      }
      if (settings.xprint(taskName)) {
        reporter.append(VerboseMessage(this.str))
      }
      if (taskName == "parse" && settings.xprint("scan")) {
        val p = new Printer
        PrettyCompiler.xprintScan(p, this)
        reporter.append(VerboseMessage(p.toString))
      }
      if (settings.ystopAfter(taskName)) {
        return
      }
      if (taskName == "parse" && settings.ystopAfter("scan")) {
        return
      }
      if (reporter.problems.nonEmpty) {
        reporter.append(ErrorSummary(reporter.problems))
        return
      }
    }
  }

  private def tasks: List[(String, () => Unit)] = List(
    "parse" -> (() => parse()),
    "index" -> (() => index()),
    "schedule" -> (() => schedule()),
    "outline" -> (() => outline()),
    "semanticdb" -> (() => semanticdb())
  )

  private def parse(): Unit = {
    val inputs = settings.ins.map(in => Input(in))
    trees = inputs.flatMap { input =>
      if (Files.exists(input.path)) {
        if (settings.ystopAfter("scan")) {
          val scanner = Scanner(settings, reporter, input)
          while (scanner.token != EOF) {
            scanner.next()
          }
          None
        } else {
          val gensym = gensyms(input)
          val parser = Parser(settings, reporter, gensym, input)
          val tree = parser.parse()
          Some(tree)
        }
      } else {
        reporter.append(FileNotFound(input))
        None
      }
    }
    if (inputs.isEmpty) {
      reporter.append(FilesNotFound())
    }
  }

  private def index(): Unit = {
    val indexer = Indexer(settings, reporter, symtab, todo)
    indexer.apply()
  }

  private def schedule(): Unit = {
    val scheduler = Scheduler(settings, reporter, gensyms, symtab, todo)
    trees.foreach(scheduler.apply(Env(), _))
  }

  private def outline(): Unit = {
    val outliner = Outliner(settings, reporter, symtab, todo)
    while (!todo.isEmpty) {
      val (env, work) = todo.remove()
      try {
        work.unblock()
        if (work.status.isPending) {
          outliner.apply(env, work)
        }
        if (work.status.isBlocked) {
          todo.add(env, work)
        }
        if (work.status.isCyclic) {
          reporter.append(IllegalCyclicReference(work))
        }
      } catch {
        case ex: Throwable =>
          val pos = work match {
            case x: ImporterScope => x.tree.pos
            case x: PackageObjectScope => x.tree.pos
            case x: TemplateScope => x.tree.pos
            case x: Sketch => x.tree.pos
            case _ => NoPosition
          }
          crash(pos, ex)
      }
    }
  }

  private def semanticdb(): Unit = {
    val semanticdb = Semanticdb(settings, reporter, gensyms, symtab)
    val outlines = new LinkedList(symtab._outlines.values)
    while (!outlines.isEmpty) {
      val outline = outlines.remove()
      try {
        semanticdb.apply(outline)
      } catch {
        case ex: Throwable =>
          crash(outline.pos, ex)
      }
    }
    semanticdb.save()
  }

  def close(): Unit = {
    symtab.close()
  }

  def printStr(p: Printer): Unit = {
    PrettyCompiler.str(p, this)
  }

  def printRepl(p: Printer): Unit = {
    PrettyCompiler.repl(p, this)
  }
}

object Compiler {
  def apply(settings: Settings, reporter: Reporter): Compiler = {
    new Compiler(settings, reporter)
  }
}
