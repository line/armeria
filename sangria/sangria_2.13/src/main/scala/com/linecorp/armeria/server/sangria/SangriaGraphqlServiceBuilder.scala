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

import com.google.common.base.Preconditions.checkArgument
import com.linecorp.armeria.common.annotation.UnstableApi
import com.linecorp.armeria.server.HttpService
import sangria.execution.deferred.DeferredResolver
import sangria.execution.{DeprecationTracker, ExceptionHandler, Middleware, QueryReducer}
import sangria.schema.Schema
import sangria.validation.QueryValidator

/**
 * A builder class for <a href="https://sangria-graphql.github.io/">Sangria</a> GraphQL service.
 */
@UnstableApi
final class SangriaGraphqlServiceBuilder[Ctx, Val] private[sangria] (
    schema: Schema[Ctx, Val],
    userContext: Ctx,
    root: Val
) {

  private var queryValidator: QueryValidator = QueryValidator.default
  private var deferredResolver: DeferredResolver[Ctx] = DeferredResolver.empty
  private var exceptionHandler: ExceptionHandler = ExceptionHandler.empty
  private var deprecationTracker: Option[DeprecationTracker] = Option.empty
  private var middleware: List[Middleware[Ctx]] = Nil
  private var maxQueryDepth: Option[Int] = None
  private var queryReducers: List[QueryReducer[Ctx, _]] = Nil
  private var useBlockingTaskExecutor: Boolean = false
  private var enableTracing: Boolean = false

  /**
   * Sets the specified [[sangria.validation.QueryValidator]].
   */
  def queryValidator(queryValidator: QueryValidator): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    this.queryValidator = queryValidator
    this
  }

  /**
   * Sets the specified [[sangria.execution.deferred.DeferredResolver]].
   */
  def deferredResolver(deferredResolver: DeferredResolver[Ctx]): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    this.deferredResolver = deferredResolver
    this
  }

  /**
   * Sets the specified [[sangria.execution.ExceptionHandler]].
   */
  def exceptionHandler(exceptionHandler: ExceptionHandler): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    this.exceptionHandler = exceptionHandler
    this
  }

  /**
   * Sets the specified [[sangria.execution.DeprecationTracker]].
   */
  def deprecationTracker(deprecationTracker: DeprecationTracker): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    this.deprecationTracker = Option(deprecationTracker)
    this
  }

  /**
   * Adds the specified [[sangria.execution.Middleware]].
   */
  def middleware(middleware: Middleware[Ctx]): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    this.middleware = this.middleware :+ middleware
    this
  }

  /**
   * Adds the specified [[sangria.execution.Middleware]]s.
   */
  def middleware(middleware: List[Middleware[Ctx]]): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    this.middleware = this.middleware ++ middleware
    this
  }

  /**
   * Sets the maximum allowed query depth.
   */
  def maxQueryDepth(maxQueryDepth: Int): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    checkArgument(maxQueryDepth > 0, "maxQueryDepth %s (expected: > 0)", maxQueryDepth)

    this.maxQueryDepth = Some(maxQueryDepth)
    this
  }

  /**
   * Adds the specified [[sangria.execution.QueryReducer]].
   */
  def queryReducer(queryReducer: QueryReducer[Ctx, _]): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    this.queryReducers = queryReducers :+ queryReducer
    this
  }

  /**
   * Adds the specified [[sangria.execution.QueryReducer]]s.
   */
  def queryReducer(queryReducers: List[QueryReducer[Ctx, _]]): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    this.queryReducers = queryReducers ++ queryReducers
    this
  }

  /**
   * Sets whether the service executes service methods using the blocking executor. By default, service
   * methods are executed directly on the event loop for implementing fully asynchronous services. If your
   * service uses blocking logic, you should either execute such logic in a separate thread using something
   * like `Executors.newCachedThreadPool()` or enable this setting.
   */
  def useBlockingTaskExecutor(useBlockingTaskExecutor: Boolean): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    this.useBlockingTaskExecutor = useBlockingTaskExecutor
    this
  }

  /**
   * Enables to log slow GraphQL queries with
   * [[https://github.com/apollographql/apollo-tracing Apollo tracing]] extension.
   * See [[https://github.com/sangria-graphql/sangria-slowlog#apollo-tracing-extension sangria-slowlog]]
   * for details.
   */
  def enableTracing(enableTracing: Boolean): SangriaGraphqlServiceBuilder[Ctx, Val] = {
    this.enableTracing = enableTracing
    this
  }

  /**
   * Returns a newly-created Sangria GraphQL service with the properties set so far.
   */
  def build(): SangriaGraphqlService[Ctx, Val] = {
    new SangriaGraphqlService(
      schema = schema,
      userContext = userContext,
      rootValue = root,
      queryValidator = queryValidator,
      deferredResolver = deferredResolver,
      exceptionHandler = exceptionHandler,
      deprecationTracker = deprecationTracker,
      middleware = middleware,
      maxQueryDepth = maxQueryDepth,
      queryReducers = queryReducers,
      useBlockingTaskExecutor = useBlockingTaskExecutor,
      enableTracing = enableTracing
    )
  }
}
