package io.tuliplogic.ziotoolbox.doobie

import doobie.hikari.HikariTransactor
import doobie.{ConnectionIO, Fragment}
import doobie.implicits._
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.instrumentation.jdbc.internal.JdbcUtils
import io.opentelemetry.instrumentation.jdbc.internal.dbinfo.DbInfo
import io.opentelemetry.semconv.SemanticAttributes
import zio.{IO, Task, ZIO}
import zio.telemetry.opentelemetry.tracing.Tracing
import zio.interop.catz._


object QueryRunner {

  object Tracing {

    private def cleanFragmentSql(f: Fragment) = f.toString().drop("Fragment(\" ".length).dropRight("\")".length)

    /**
     *
     * `import io.tuliplogic.ziotoolbox.doobie.QueryRunner.Tracing.syntax._` to get some convenience methods to run traced
     * transactions.
     *
     *   1. If you have a `Fragment` at hand, use `runTraced` to derive automatically the sql and use it as a name for
     *      the span surrounding the execution
     * {{{
     * val fragment: Fragment = ???
     * val tx: HikariTransactor[Task] = ???
     * val tracing: Tracing = ???
     * val io: IO[DBError, Int] = fragment.runTraced(_.update.run)(tracing, tx)
     *       }}}
     *
     * The `runTracedEnvironment` version expects tracing and transactor to come from ZIO environment
     *
     * 2. If you have just a `ConnectionIO[A]`, use `runTraced` to derive automatically the sql and use it as a name
     * for the span surrounding the execution
     * {{{
     * val cx: ConnectionIO[Int] = ???
     * val tx: HikariTransactor[Task] = ???
     * val tracing: Tracing = ???
     * val io: IO[DBError, Int] = cx.transactTraced(tx, "sql-span", "select 1", tracing)
     * }}}
     */
    object syntax {
      private case class TracedConnectionIO[O](sql: String, span: String, cx: ConnectionIO[O]) {

        private def runWithSqlAttributes(
                                             tracing: Tracing,
                                             dataSource: HikariTransactor[Task]#A,
                                             spanName: String,
                                             sql: String,
                                             extraAttributes: (DbInfo, String) => Attributes
                                           )(zio: IO[DBError, O]): IO[DBError, O] = {

          for {
            dbInfo <- ZIO.succeed(JdbcUtils.computeDbInfo(dataSource.getConnection))
            res <- zio @@ tracing.aspects.span(
              spanName = "db:" + spanName,
              spanKind = SpanKind.CLIENT,
              attributes = Attributes
                .builder()
                .put(SemanticAttributes.DB_SYSTEM, dbInfo.getDb)
                .put(SemanticAttributes.DB_USER, dbInfo.getUser)
                .put(SemanticAttributes.DB_STATEMENT, sql)
                .putAll(extraAttributes(dbInfo, sql))

                .build()
            )
          } yield res
        }

        /**
         * Run the query, wrapping it in a span with the given name and sql attribute.
         * Basic DBInfo are derived from the connection and added to the span attributes.
         * Extra attributes can be derived and added through the `extraAttributes` function parameter
         * For Datadog:
         * {{{
         * //            .put(DatadogTags.DB_OPERATION, sql.trim.split(" ").headOption.getOrElse("---"))
         * //            .put(DatadogTags.COMPONENT, DatadogTags.Jdbc.JDBC_PREPARED_STATEMENT)
         * //            .put(DatadogTags.DB_INSTANCE, dbInfo.instance)
         * //            .put(DatadogTags.PEER_HOSTNAME, dbInfo.host)
         * }}}
         *
         */
        def runTracedQuery(tracing: Tracing, transactor: HikariTransactor[Task], extraAttributes: (DbInfo, String) => Attributes = (_, _) => Attributes.empty): IO[DBError, O] =
          runWithSqlAttributes(tracing, transactor.kernel, span, sql, extraAttributes)(
            cx.transact(transactor)
              .tapErrorCause(cause => ZIO.logErrorCause("Error calling database", cause))
              .mapError(e => DBError(s"Error calling database", Some(e)))
          )
      }
      implicit class TracedFragment(f: Fragment) {
        private def traced[A](run: Fragment => ConnectionIO[A]): TracedConnectionIO[A] = {
          val sql = cleanFragmentSql(f)
          TracedConnectionIO(sql = sql, span = sql, run(f))
        }

        def runTracedEnvironment[A](
                                     run: Fragment => ConnectionIO[A]
                                   ): ZIO[HikariTransactor[Task] with Tracing, DBError, A] = for {
          tracing    <- ZIO.service[Tracing]
          transactor <- ZIO.service[HikariTransactor[Task]]
          res        <- runTraced(run)(transactor, tracing)
        } yield res

        def runTraced[A](
                          run: Fragment => ConnectionIO[A]
                        )(transactor: HikariTransactor[Task], tracing: Tracing): IO[DBError, A] =
          traced(run).runTracedQuery(tracing, transactor)
      }

      implicit class TracedConnection[A](cx: ConnectionIO[A]) {
        def transactTraced(
                            tx: HikariTransactor[Task],
                            tracing: Tracing,
                            spanName: String,
                            sql: String
                          ): IO[DBError, A] =
          TracedConnectionIO(sql, spanName, cx).runTracedQuery(tracing, tx)
      }
    }


  }
}
