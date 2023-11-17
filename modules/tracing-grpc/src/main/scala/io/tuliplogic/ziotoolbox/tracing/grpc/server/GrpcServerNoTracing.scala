package io.tuliplogic.ziotoolbox.tracing.grpc.server

import io.grpc.StatusException
import scalapb.zio_grpc.{GTransform, GeneratedService, RequestContext, ZTransform}
import zio.stream.ZStream
import zio.{Tag, ZIO, ZLayer}

object GrpcServerNoTracing {
  def service[R: Tag, Service <: GeneratedService](
                                                               f: R => Service,
                                                             )(implicit
                                                               serviceTag: Tag[Service#Generic[RequestContext, StatusException]]
                                                             ): ZLayer[R, Nothing, Service#Generic[RequestContext, StatusException]] =
    ZLayer.fromZIO {
      for {
        env <- ZIO.service[R]
        idZTransform: ZTransform[Any, RequestContext] = new ZTransform[Any, RequestContext] {
          override def effect[A](io: Any => ZIO[Any, StatusException, A]): RequestContext => ZIO[Any, StatusException, A] = rc => io(rc)

          override def stream[A](io: Any => ZStream[Any, StatusException, A]): RequestContext => ZStream[Any, StatusException, A] = rc => io(rc)
        }
      } yield f(env).transform(idZTransform)
    }
}
