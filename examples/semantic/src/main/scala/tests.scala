package tickettests

object O {
  private[tickettests] def foo: Nothing = ???

  private[tickettests] class C(x: Int = 0, val y: String = "") {
    override def toString: String = "oc"
  }
}

trait T {
  def toString: String
}
class TC extends T {
  override def toString: String = "tc"
}

case class S(x: Int, ys: String*)
