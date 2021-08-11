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
package com.linecorp.armeria.scala

import java.util.concurrent.CompletionStage

import com.linecorp.armeria.common.annotation.UnstableApi
import com.linecorp.armeria.common.{HttpResponse, RequestContext}

import scala.compat.java8.{DurationConverters, FutureConverters}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

@UnstableApi
trait CommonConversions {

  // Add CompletionStage[HttpResponse].toHttpResponse
  implicit final def httpResponseCompletionStageOps(
      stage: CompletionStage[HttpResponse]): HttpResponseCompletionStageOps =
    new HttpResponseCompletionStageOps(stage)

  // Add CompletionStage[Void].toScala
  implicit final def voidCompletionStageOps(stage: CompletionStage[Void]): VoidCompletionStageOps =
    new VoidCompletionStageOps(stage)

  // Add CompletionStage.toScala
  implicit final def completionStageOps[T](stage: CompletionStage[T]): FutureConverters.CompletionStageOps[T] =
    new FutureConverters.CompletionStageOps[T](stage)

  // Add Future[HttpResponse].toHttpResponse
  implicit final def httpResponseFutureOps(future: Future[HttpResponse]): HttpResponseFutureOps =
    new HttpResponseFutureOps(future)

  // Add Future[Unit].toJava
  implicit final def unitFutureOps(future: Future[Unit]): UnitFutureOps =
    new UnitFutureOps(future)

  // Add Future.toJava
  implicit final def futureOps[T](f: Future[T]): FutureConverters.FutureOps[T] =
    new FutureConverters.FutureOps[T](f)

  // Add RequestContext.eventLoopExecutionContext
  implicit final def requestContextOps(ctx: RequestContext): RequestContextOps = new RequestContextOps(ctx)

  // Convert FiniteDuration to java.time.Duration
  implicit final def finiteDurationToJavaDuration(finiteDuration: FiniteDuration): java.time.Duration =
    DurationConverters.toJava(finiteDuration)

  // Convert java.time.Duration to FiniteDuration
  implicit final def javaDurationToFiniteDuration(duration: java.time.Duration): FiniteDuration =
    DurationConverters.toScala(duration)
}

@UnstableApi
final class HttpResponseCompletionStageOps(private val stage: CompletionStage[HttpResponse]) extends AnyVal {

  /**
   * Converts this `CompletionStage` into an `HttpResponse` using `HttpResponse.from(CompletionStage)`.
   */
  def toHttpResponse: HttpResponse = HttpResponse.from(stage)
}

@UnstableApi
final class VoidCompletionStageOps(private val future: CompletionStage[Void]) extends AnyVal {

  /**
   * Returns a Scala `Future` that will be completed with `Unit` or exception as the given `CompletionStage`
   * completes. Transformations of the returned Future are executed asynchronously as specified by the
   * `ExecutionContext` that is given to the combinator methods.
   *
   * @return a Scala `Future` that represents the `CompletionStage`'s completion
   */
  def toScala: Future[Unit] = FutureConverters.toScala[Unit](future.thenApply(_ => ()))
}

@UnstableApi
final class HttpResponseFutureOps(private val future: Future[HttpResponse]) extends AnyVal {

  /**
   * Converts this `Future` into an `HttpResponse` using `HttpResponse.from(CompletionStage)`.
   */
  def toHttpResponse: HttpResponse = HttpResponse.from(FutureConverters.toJava(future))
}

@UnstableApi
final class UnitFutureOps(private val future: Future[Unit]) extends AnyVal {

  /**
   * Returns a `CompletionStage` that will be completed with `null` or exception as the given Scala `Future`
   * completes. Since the Future is a read-only representation, this CompletionStage does not support the
   * `toCompletableFuture` method. The semantics of Scala `Future` demand that all callbacks are invoked
   * asynchronously by default, therefore the returned CompletionStage routes all calls to synchronous
   * transformations to their asynchronous counterparts, e.g. `thenRun` will internally call `thenRunAsync`.
   *
   * @return a `CompletionStage` that runs all callbacks asynchronously and does not support
   *         the `CompletableFuture` interface
   */
  def toJava: CompletionStage[Void] = FutureConverters.toJava(future).thenApply(_ => null)
}

@UnstableApi
final class RequestContextOps(private val ctx: RequestContext) extends AnyVal {

  /**
   * Returns the `ExecutionContext` that executes all tasks via the `ContextAwareEventLoop` that is handling
   * the current request. The returned `ExecutionContext` sets this `RequestContext` as the current context
   * before executing any submitted tasks.
   */
  def eventLoopExecutionContext: ExecutionContext = ExecutionContext.fromExecutorService(ctx.eventLoop)
}
