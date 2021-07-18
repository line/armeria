/*
 * Copyright 2021 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.sangria

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.linecorp.armeria.common.HttpStatus.{BAD_REQUEST, INTERNAL_SERVER_ERROR}
import com.linecorp.armeria.common.annotation.UnstableApi
import com.linecorp.armeria.common.{HttpHeaders, HttpResponse}
import com.linecorp.armeria.internal.server.JacksonUtil
import com.linecorp.armeria.scala.implicits._
import com.linecorp.armeria.server.graphql.protocol.{AbstractGraphqlService, GraphqlRequest}
import com.linecorp.armeria.server.sangria.SangriaGraphqlService.mapper
import com.linecorp.armeria.server.sangria.SangriaJackson._
import com.linecorp.armeria.server.{HttpService, ServiceRequestContext}
import sangria.execution._
import sangria.execution.deferred.DeferredResolver
import sangria.parser.{QueryParser, SyntaxError}
import sangria.schema.Schema
import sangria.slowlog.SlowLog
import sangria.validation.QueryValidator
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

private final class SangriaGraphqlService[Ctx, Val](
    schema: Schema[Ctx, Val],
    userContext: Ctx,
    rootValue: Val,
    queryValidator: QueryValidator,
    deferredResolver: DeferredResolver[Ctx],
    exceptionHandler: ExceptionHandler,
    deprecationTracker: DeprecationTracker,
    middleware: List[Middleware[Ctx]],
    maxQueryDepth: Option[Int],
    queryReducers: List[QueryReducer[Ctx, _]],
    useBlockingTaskExecutor: Boolean,
    enableTracing: Boolean
) extends AbstractGraphqlService {

  override def executeGraphql(ctx: ServiceRequestContext, req: GraphqlRequest): HttpResponse = {
    QueryParser.parse(req.query()) match {
      case Success(queryAst) =>
        val tracing = isTracingEnabled(ctx.request().headers())
        val variables: JsonNode = mapper.valueToTree(req.variables())
        implicit val executionContext: ExecutionContext =
          if (useBlockingTaskExecutor) {
            ExecutionContext.fromExecutorService(ctx.blockingTaskExecutor())
          } else {
            ctx.eventLoopExecutionContext
          }

        Executor
          .execute(
            schema = schema,
            queryAst = queryAst,
            userContext = userContext,
            root = rootValue,
            operationName = Option(req.operationName()),
            variables = variables,
            queryValidator = queryValidator,
            deferredResolver = deferredResolver,
            exceptionHandler = exceptionHandler,
            deprecationTracker = deprecationTracker,
            middleware = if (tracing) SlowLog.apolloTracing :: middleware else middleware,
            maxQueryDepth = maxQueryDepth,
            queryReducers = queryReducers
          )
          .map(HttpResponse.ofJson)
          .recover {
            case error: QueryAnalysisError => HttpResponse.ofJson(BAD_REQUEST, error.resolveError)
            case error: ErrorWithResolver  => HttpResponse.ofJson(INTERNAL_SERVER_ERROR, error.resolveError)
          }
          .toHttpResponse

      // Can't parse GraphQL query, return error
      case Failure(error: SyntaxError) =>
        val rootNode = mapper.createObjectNode()
        rootNode.put("syntaxError", error.getMessage)
        rootNode
          .putArray("locations")
          .addObject()
          .put("line", error.originalError.position.line)
          .put("column", error.originalError.position.column)

        HttpResponse.ofJson(BAD_REQUEST, rootNode)

      case Failure(error) =>
        HttpResponse.ofFailure(error)
    }
  }

  private def isTracingEnabled(headers: HttpHeaders): Boolean = {
    enableTracing && headers.contains("X-Apollo-Tracing")
  }
}

/**
 * A [[https://sangria-graphql.github.io/ Sangria GraphQL]] service.
 */
@UnstableApi
object SangriaGraphqlService {

  private val mapper: ObjectMapper = JacksonUtil.newDefaultObjectMapper()

  /**
   * Returns a newly-created [[com.linecorp.armeria.server.sangria.SangriaGraphqlServiceBuilder]] that builds
   * a Sangria GraphQL service.
   * @param schema the GraphQL schema
   * @param userContext the user context of the specified `schema`
   * @param rootValue the root value of the specified `schema`
   *
   * Example:
   * {{{
   * val schema: Schema[CharacterRepo, Unit] = Schema(Query)
   * Server.builder()
   *       .service("/graphql, SangriaGraphqlService.builder(schema, new CharacterRepo)
   *                                                .enableTracing(true)
   *                                                .maxQueryDepth(10)
   *                                                .build())
   * }}}
   */
  def builder[Ctx, Val](
      schema: Schema[Ctx, Val],
      userContext: Ctx = (),
      rootValue: Val = ()): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    new SangriaGraphqlServiceBuilder(schema, userContext, rootValue)
  }

  /**
   * Returns a newly-created Sangria GraphQL service.
   * @param schema the GraphQL schema
   * @param userContext the user context of the specified `schema`
   * @param rootValue the root value of the specified `schema`
   *
   * Example:
   * {{{
   * val schema: Schema[CharacterRepo, Unit] = Schema(Query)
   * Server.builder()
   *       .service("/graphql, SangriaGraphqlService(schema, new CharacterRepo))
   * }}}
   */
  def apply[Ctx, Val](schema: Schema[Ctx, Val], userContext: Ctx = (), rootValue: Val = ()): HttpService = {
    builder(schema, userContext, rootValue).build()
  }
}
