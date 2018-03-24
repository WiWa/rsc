// Copyright (c) 2017-2018 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package rsc.bench

import rsc.Compiler
import rsc.report._
import rsc.settings._

trait RscFixtures extends FileFixtures {
  def mkCompiler(args: Any*): Compiler = {
    val stdlibOptions = List("-cp", stdlibClasspath)
    val userOptions = args.flatMap {
      case seq: Seq[_] => seq.map(_.toString)
      case other => List(other.toString)
    }
    val options = stdlibOptions ++ userOptions
    val settings = Settings.parse(options.toList).get
    val reporter = StoreReporter(settings)
    Compiler(settings, reporter)
  }
}
