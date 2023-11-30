# ZIO-Theseus
### A collection of libraries to instrument ZIO-based service boundaries using ZIO-OpenTelemetry

N.B.: This projects is a work in progress and at a very early stage, so it is not published.

It focuses on Tracing utilities based on [ZIO-OpenTelemetry](https://github.com/zio/zio-telemetry/tree/series/2.x/opentelemetry/src).

It also contains a library with some utilities to work with Doobie and Flyway, that will eventually migrate to a different project.

### Modules:
- `doobie`: Provides a standard layer to get a `Transactor` and `Flyway` migrations. (This is not about tracing, it will be eventually moved out of this library)
- `tracing-commons`: Shared utilities to initialize the application so that Tracing and Logging are available
- `tracing-doobie`: instrumentation around the execution of doobie fragments
- `tracing-sttp`: instrumentation around the execution of http requests on the client side (using sttp) or on the server side (using http4s)
- `tracing-grpc`: instrumentation around the execution of grpc requests on the client side and server side (using [zio-grpc](https://github.com/scalapb/zio-grpc))
- `tracing-kafka`: instrumentation around zio-kafka producer and consumer

### Simple Example
A simple example application is available at `modules/simple-example`. Run `docker-compose up` from that directory. It provides the observability stack (Grafana Tempo, Grafana Loki) + the application stack (Kafka, Postgres).
The collection of logs in Loki doesn't fully work yet, but the traces in Tempo are working fine.

### Real World Example
A more real world example is available in `modules/example` directory, where you will find 3 services that can be run independently.  the `http-calls/calls.http` file contains the calls to start and stop sessions targeting the charging service.

For my experiments I'm using Datadog which provides seamless integration between logs and traces, but there is work in progress to have this setup working with Grafana/Loki.


