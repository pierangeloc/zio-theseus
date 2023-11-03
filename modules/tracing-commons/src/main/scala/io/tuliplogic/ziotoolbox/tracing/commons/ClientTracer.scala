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
  def makeAttributes(map: Map[String, String]): Attributes =
    map.foldLeft(Attributes.builder())((builder, kv) => builder.put(kv._1, kv._2))
      .build()

  case class Const[Req, Res](attrs: Map[String, String]) extends TracerAlgebra[Req, Res] {
    override def spanName(request: Req): String = ""
    override def requestAttributes(req: Req): Map[String, String] = attrs
    override def responseAttributes(res: Res): Map[String, String] = attrs
  }

  case class Composed[Req, Res](before: TracerAlgebra[Req, Res], after: TracerAlgebra[Req, Res]) extends TracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = before.requestAttributes(req)
    override def responseAttributes(res: Res): Map[String, String] = after.responseAttributes(res)
    override def spanName(request: Req): String = after.spanName(request)
  }

  case class SpanName[Req, Res](extractSpanName: Req => String) extends TracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = Map.empty
    override def responseAttributes(res: Res): Map[String, String] = Map.empty
    override def spanName(request: Req): String = extractSpanName(request)
  }

  case class WithRequestAttributes[Req, Res](extractRequestAttributes: Req => Map[String, String]) extends TracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = extractRequestAttributes(req)
    override def responseAttributes(res: Res): Map[String, String] = Map.empty
    override def spanName(request: Req): String = ""
  }

  case class WithResponseAttributes[Req, Res](extractResponseAttributes: Res => Map[String, String]) extends TracerAlgebra[Req, Res] {
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
