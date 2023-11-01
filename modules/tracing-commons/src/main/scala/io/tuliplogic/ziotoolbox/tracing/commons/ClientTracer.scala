package io.tuliplogic.ziotoolbox.tracing.commons

trait ClientTracerAlgebra[Req, Res] {
  def spanName(request: Req): String
  def requestAttributes(req: Req): Map[String, String]
  def responseAttributes(res: Res): Map[String, String]

  def &(other: ClientTracerAlgebra[Req, Res]): ClientTracerAlgebra[Req, Res] = ClientTracerAlgebra.Composed(this, other)
}

object ClientTracerAlgebra {
  case class Const[Req, Res](attrs: Map[String, String]) extends ClientTracerAlgebra[Req, Res] {
    override def spanName(request: Req): String = ""
    override def requestAttributes(req: Req): Map[String, String] = attrs
    override def responseAttributes(res: Res): Map[String, String] = attrs
  }

  case class Composed[Req, Res](before: ClientTracerAlgebra[Req, Res], after: ClientTracerAlgebra[Req, Res]) extends ClientTracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = before.requestAttributes(req)
    override def responseAttributes(res: Res): Map[String, String] = after.responseAttributes(res)
    override def spanName(request: Req): String = after.spanName(request)
  }

  case class SpanName[Req, Res](extractSpanName: Req => String) extends ClientTracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = Map.empty
    override def responseAttributes(res: Res): Map[String, String] = Map.empty
    override def spanName(request: Req): String = extractSpanName(request)
  }

  case class WithRequestAttributes[Req, Res](extractRequestAttributes: Req => Map[String, String]) extends ClientTracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = extractRequestAttributes(req)
    override def responseAttributes(res: Res): Map[String, String] = Map.empty
    override def spanName(request: Req): String = ""
  }

  case class WithResponseAttributes[Req, Res](extractResponseAttributes: Res => Map[String, String]) extends ClientTracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = Map.empty
    override def responseAttributes(res: Res): Map[String, String] = extractResponseAttributes(res)
    override def spanName(request: Req): String = ""
  }


  class Dsl[Req, Res] {

    def const(attrs: Map[String, String] = Map()): ClientTracerAlgebra[Req, Res] = Const[Req, Res](attrs)

    def spanName(extractSpanName: Req => String): ClientTracerAlgebra[Req, Res] = SpanName[Req, Res](extractSpanName)

    def withRequestAttributes(extractRequestAttributes: Req => Map[String, String]): ClientTracerAlgebra[Req, Res] = WithRequestAttributes[Req, Res](extractRequestAttributes)

    def withResponseAttributes(extractResponseAttributes: Res => Map[String, String]): ClientTracerAlgebra[Req, Res] = WithResponseAttributes[Req, Res](extractResponseAttributes)
  }


  def dsl[Req, Res] = new Dsl[Req, Res]
}
