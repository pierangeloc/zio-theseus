package io.tuliplogic.ziotoolbox.tracing.commons

import io.opentelemetry.api.common.Attributes


/**
 * A tracer algebra. When one of the type parameters is set to Any, it means it is not used
 *
 * This is a declarative algebra to define how to make attributes out of request, and responses.
 * The objective is to have a simple DSL that defines how a span for outgoing or incoming request
 * has its name and attributes defined.
 *
 * Interpretations of this algebra provide the instrumentation for the different technologies
 * interested by this tracing library
 *
 * Another interpretation we are exploring is the documentation instrumentation.
 *
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

  private case class RequestAttributes[Req, Res](extractRequestAttributes: Req => Map[String, String]) extends TracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = extractRequestAttributes(req)
    override def responseAttributes(res: Res): Map[String, String] = Map.empty
    override def spanName(request: Req): String = ""
  }

  private case class ResponseAttributes[Req, Res](extractResponseAttributes: Res => Map[String, String]) extends TracerAlgebra[Req, Res] {
    override def requestAttributes(req: Req): Map[String, String] = Map.empty
    override def responseAttributes(res: Res): Map[String, String] = extractResponseAttributes(res)
    override def spanName(request: Req): String = ""
  }


  class Dsl[Req, Res] {

    def const(attrs: Map[String, String] = Map()): TracerAlgebra[Req, Res] = requestAttributes(_ => attrs)

    def spanName(extractSpanName: Req => String): TracerAlgebra[Req, Res] = SpanName[Req, Res](extractSpanName)

    def requestAttributes(extractRequestAttributes: Req => Map[String, String]): TracerAlgebra[Req, Res] = RequestAttributes[Req, Res](extractRequestAttributes)

    def responseAttributes(extractResponseAttributes: Res => Map[String, String]): TracerAlgebra[Req, Res] = ResponseAttributes[Req, Res](extractResponseAttributes)
  }


  def dsl[Req, Res] = new Dsl[Req, Res]
}
