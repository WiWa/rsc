package tickettests

import java.util.concurrent.atomic.AtomicInteger

trait T {
  def toString: String
}
class TC extends T {
  override def toString: String = "tc"
}
class TD extends T {
  override def toString(): String = "tc"
}

trait T2 {
  def toString(): String
}
trait T3 {
  override def toString: String = ""
}
// this has a parameter list!
//trait T4 {
//  override def toString: String
//}
trait T5 extends T3 {
  override def toString: String = ""
}
trait T6 extends T2 {
  def toString: String
}
class C7 extends T6 {
  override def toString: String = ""
}

case class S(x: Int, ys: String*)

object O {
  private[O] def foo(x: Int = 0): Nothing = ???

  class C private[O](x: Int = 0, val y: C7 = new C7) {
    private[O] def foo(x: Int = 0): Nothing = ???
  }
}

class STC2 extends STC {
  println(serialized(1))

  override def asdf = {
    super.asdf
    "as"
  }
}
