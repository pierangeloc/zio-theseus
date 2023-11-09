package io.tuliplogic.ziotoolbox.tracing.commons

import io.opentelemetry.api.common.Attributes


/**
 * A tracer algebra. When one of the type parameters is set to Any, it means it is not used
 *
 * @tparam Req
 * @tparam Res
 */
trait TracerAlgebra[Req, Res] {
  def spanName(request: Req): String
  def requestAttributes(req: Req): Map[String, String]
  def responseAttributes(res: Res): Map[String, String]

  def &(other: TracerAlgebra[Req, Res]): TracerAlgebra[Req, Res] = TracerAlgebra.Composed(this, other)
}

object TracerAlgebra {

  private case class Const[Req, Res](attrs: Map[String, String]) extends TracerAlgebra[Req, Res] {
    override def spanName(request: Req): String = ""
    override def requestAttributes(req: Req): Map[String, String] = attrs
    override def responseAttributes(res: Res): Map[String, String] = attrs
  }

  private case class Composed[Req, Res](first: TracerAlgebra[Req, Res], second: TracerAlgebra[Req, Res]) extends TracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = first.requestAttributes(req) ++ second.requestAttributes(req)
    override def responseAttributes(res: Res): Map[String, String] = first.responseAttributes(res) ++ second.responseAttributes(res)
    override def spanName(request: Req): String = List(first.spanName(request), second.spanName(request)).find(_.nonEmpty).getOrElse("")
  }

  private case class SpanName[Req, Res](extractSpanName: Req => String) extends TracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = Map.empty
    override def responseAttributes(res: Res): Map[String, String] = Map.empty
    override def spanName(request: Req): String = extractSpanName(request)
  }

  private case class WithRequestAttributes[Req, Res](extractRequestAttributes: Req => Map[String, String]) extends TracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = extractRequestAttributes(req)
    override def responseAttributes(res: Res): Map[String, String] = Map.empty
    override def spanName(request: Req): String = ""
  }

  private case class WithResponseAttributes[Req, Res](extractResponseAttributes: Res => Map[String, String]) extends TracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = Map.empty
    override def responseAttributes(res: Res): Map[String, String] = extractResponseAttributes(res)
    override def spanName(request: Req): String = ""
  }


  class Dsl[Req, Res] {

    def const(attrs: Map[String, String] = Map()): TracerAlgebra[Req, Res] = Const[Req, Res](attrs)

    def spanName(extractSpanName: Req => String): TracerAlgebra[Req, Res] = SpanName[Req, Res](extractSpanName)

    def withRequestAttributes(extractRequestAttributes: Req => Map[String, String]): TracerAlgebra[Req, Res] = WithRequestAttributes[Req, Res](extractRequestAttributes)

    def withResponseAttributes(extractResponseAttributes: Res => Map[String, String]): TracerAlgebra[Req, Res] = WithResponseAttributes[Req, Res](extractResponseAttributes)
  }


  def dsl[Req, Res] = new Dsl[Req, Res]
}
