// Copyright (c) 2017-2018 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package rsc.settings

import java.io._
import java.nio.file._

final case class Settings(
    abi: Abi = Scalac211,
    cp: List[Path] = Nil,
    debug: Boolean = false,
    ins: List[Path] = Nil,
    out: Path = Paths.get("out.semanticdb"),
    xprint: Set[String] = Set[String](),
    ystopAfter: Set[String] = Set[String]()
)

// FIXME: https://github.com/twitter/rsc/issues/166
object Settings {
  def parse(args: List[String]): Option[Settings] = {
    def loop(settings: Settings, allowOptions: Boolean, args: List[String]): Option[Settings] = {
      args match {
        case "--" +: rest =>
          loop(settings, false, rest)
        case ("-classpath" | "-cp") +: s_cp +: rest if allowOptions =>
          val cp = s_cp.split(File.pathSeparator).map(s => Paths.get(s)).toList
          loop(settings.copy(cp = settings.cp ++ cp), true, rest)
        case "-debug" +: rest if allowOptions =>
          loop(settings.copy(debug = true), true, rest)
        case "-release" +: rest if allowOptions =>
          loop(settings.copy(debug = false), true, rest)
        case "-out" +: s_out +: rest if allowOptions =>
          val out = Paths.get(s_out)
          loop(settings.copy(out = out), true, rest)
        case "-abi" +: s_abi +: rest if allowOptions =>
          s_abi match {
            case "scalac211" =>
              loop(settings.copy(abi = Scalac211), true, rest)
            case "scalac212" =>
              loop(settings.copy(abi = Scalac212), true, rest)
            case other =>
              println(s"unknown abi $other")
              loop(settings, true, rest)
          }
        case opt +: rest if allowOptions && opt.startsWith("-Xprint:") =>
          val stripped = opt.stripPrefix("-Xprint:").split(",")
          val xprint = stripped.map(_.trim).toSet
          val xprint1 = settings.xprint ++ xprint
          loop(settings.copy(xprint = xprint1), true, rest)
        case opt +: rest if allowOptions && opt.startsWith("-Ystop-after:") =>
          val stripped = opt.stripPrefix("-Ystop-after:").split(",")
          val ystopAfter = stripped.map(_.trim).toSet
          val ystopAfter1 = settings.ystopAfter ++ ystopAfter
          loop(settings.copy(ystopAfter = ystopAfter1), true, rest)
        case flag +: rest if allowOptions && flag.startsWith("-") =>
          println(s"unknown flag $flag")
          None
        case in +: rest =>
          val ins = List(Paths.get(in))
          loop(settings.copy(ins = settings.ins ++ ins), allowOptions, rest)
        case Nil =>
          Some(settings)
      }
    }
    loop(Settings(), true, args)
  }
}
