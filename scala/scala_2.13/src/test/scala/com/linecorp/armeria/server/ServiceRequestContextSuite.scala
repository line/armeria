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
package com.linecorp.armeria.server

import java.util.concurrent.atomic.AtomicBoolean

import com.linecorp.armeria.common.{HttpMethod, HttpRequest}
import com.linecorp.armeria.scala.implicits._
import munit.FunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class ServiceRequestContextSuite extends FunSuite {
  test("RequestContext.blockingTaskExecutionContext") {
    val ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"))
    implicit val ec: ExecutionContext = ctx.blockingTaskExecutionContext
    val invoked = new AtomicBoolean()

    Await.ready(
      Future {
        assert(ServiceRequestContext.current == ctx)
        invoked.set(true)
      },
      10.seconds)

    assert(invoked.get())
  }

  test("Should be able to configure a ServerBuilder with a lambda expression") {
    ServiceRequestContext
      .builder(HttpRequest.of(HttpMethod.GET, "/"))
      .serverConfigurator { serverBuilder =>
        serverBuilder.accessLogger("foo")
      }
  }
}
