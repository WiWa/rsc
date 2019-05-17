package tickettests

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
trait T4 {
  override def toString: String
}
trait T5 extends T3 {
  override def toString: String = ""
}
trait T6 extends T2 {
  def toString: String
}
class C7 extends T6 {
  override def toString: String = ""
}

//case class S(x: Int, ys: String*)
