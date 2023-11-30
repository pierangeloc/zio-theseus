package io.tuliplogic.ziotoolbox.tracing.commons

trait Show[A] {
  def show(a: A): String
}

object Show {
  def apply[A](implicit ev: Show[A]): Show[A] = ev
}
