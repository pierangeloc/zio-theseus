Observability is good because:
- Debugging and Troubleshooting:
- Proactive Issue Resolution (Early error detection)
- Crucial in distributed systems

Ways to achieve observability:
- Simple logs -> show an example
- Structured logs -> show an example
- We need to pass context between services


Steps to build an instrumented Grpc application:

1. Override ZIO default loggers
2. make a class that extends the ZIOXXXAPI
3. define a method that returns a Server, given the ZIOXXXAPI, by adding the service to the service list
3. Build a layer that provides the ZIOXXXAPI `GrpcServerTracingInterpreter.serviceWithTracing` method, that will add and use all the tracing dependencies
4.
