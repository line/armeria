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

import java.util.function.{Consumer => JConsumer, Function => JFunction}

import com.linecorp.armeria.common.annotation.UnstableApi
import com.linecorp.armeria.common.{HttpRequest, HttpResponse, RpcRequest, RpcResponse}
import com.linecorp.armeria.server._

import scala.concurrent.ExecutionContext
import scala.language.implicitConversions

@UnstableApi
trait ServerConversions {

  // Convert a Scala function to HttpService.
  // ServerBuilderSuite fails without this.
  implicit final def funcToHttpService(
      func: (ServiceRequestContext, HttpRequest) => HttpResponse
  ): HttpService = (ctx: ServiceRequestContext, req: HttpRequest) => func(ctx, req)

  // Convert a Scala function to RpcService.
  implicit final def funcToRpcService(
      func: (ServiceRequestContext, RpcRequest) => RpcResponse
  ): RpcService = (ctx: ServiceRequestContext, req: RpcRequest) => func(ctx, req)

  // Convert a Scala function to Consumer[Server]
  // ServerListenerBuilderSuite fails without this.
  implicit final def funcToJavaConsumer(func: Server => Any): JConsumer[Server] = func(_)

  // Add an extension method that provides an ExecutionContext for a context-aware blocking task executor.
  implicit final def serviceRequestContextOps(ctx: ServiceRequestContext): ServiceRequestContextOps =
    new ServiceRequestContextOps(ctx)

  // Make {Http,Rpc}Service.decorate(Function) work.
  // ServerBuilderSuite fails without this.
  implicit final def serviceOps[T <: Service[_, _]](service: T): ServiceOps[T] = new ServiceOps[T](service)
}

@UnstableApi
final class ServiceOps[T <: Service[_, _]](private val service: T) extends AnyVal {
  def decorate[U >: T, R <: Service[_, _]](decorator: JFunction[U, R]): R = {
    val newService = decorator(service)

    if (newService == null) {
      throw new NullPointerException(s"decorator.apply() returned null: $decorator")
    }

    newService
  }
}

@UnstableApi
final class ServiceRequestContextOps(private val ctx: ServiceRequestContext) extends AnyVal {

  /**
   * Returns the `ExecutionContext` that could be used for executing a potentially long-running task.
   * The returned `ExecutionContext` sets this `RequestContext` as the current context before executing
   * any submitted tasks.
   */
  def blockingTaskExecutionContext: ExecutionContext =
    ExecutionContext.fromExecutorService(ctx.blockingTaskExecutor)
}
