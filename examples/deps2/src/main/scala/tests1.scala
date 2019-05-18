package tickettests1

import java.util.concurrent.atomic.AtomicInteger

trait ST {

  private[this] val nwaiters: AtomicInteger = new AtomicInteger(0)


  protected def serialized[A](f: => A): Seq[A] = {
    val result = f

    if (nwaiters.getAndIncrement() == 0) {
      do {
        println("asd")
      } while (nwaiters.decrementAndGet() > 0)
    }

    Seq(result)
  }

  def asdf: String = ""
}
