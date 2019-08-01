package rsc.lexis.common

import rsc.pretty.{PrettyToken, Printer}

trait Tokens {
  final val BOF = 1
  final val ERROR = 2
  final val EOF = 3

  type Token = Int

  def tokenStr(token: Token): String = {
    val p = new Printer
    PrettyToken.str(p, token)
    p.toString
  }

  def tokenRepl(token: Token): String = {
    val p = new Printer
    PrettyToken.repl(p, token)
    p.toString
  }
}
