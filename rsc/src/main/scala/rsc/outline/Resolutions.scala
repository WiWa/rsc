// Copyright (c) 2017-2018 Twitter, Inc.
// Licensed under the Apache License, Version 2.0 (see LICENSE.md).
package rsc.outline

import rsc.pretty._
import rsc.semantics._

sealed trait Resolution extends Pretty with Product {
  override def printStr(p: Printer): Unit = PrettyResolution.str(p, this)
  override def printRepl(p: Printer): Unit = PrettyResolution.repl(p, this)
}

final case class BlockedResolution(work: Work) extends Resolution
sealed trait FailedResolution extends Resolution
case object AmbiguousResolution extends FailedResolution
case object MissingResolution extends FailedResolution
case object ErrorResolution extends FailedResolution
final case class FoundResolution(sym: Symbol) extends Resolution
