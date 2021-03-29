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

import com.linecorp.armeria.common.{HttpRequest, HttpResponse, HttpStatus, MediaType}
import com.linecorp.armeria.scala.implicits._
import com.linecorp.armeria.server.logging.LoggingService
import com.linecorp.armeria.server.throttling.{ThrottlingService, ThrottlingStrategy}
import munit.FunSuite
import org.slf4j.LoggerFactory

class ServerBuilderSuite extends FunSuite {

  private val service = new HttpService {
    override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse =
      HttpResponse.of(200)
  }

  test("Should be able to bind a service using a lambda expression") {
    Server
      .builder()
      .service(
        "/greet/{name}",
        (ctx, req) =>
          HttpResponse.of(
            HttpStatus.OK,
            MediaType.PLAIN_TEXT_UTF_8,
            s"Hello, ${ctx.pathParam("name")}! (Path: ${req.path})"))
      .build()
  }

  test("Should be able to decorate a service with HttpService.decorate()") {
    Server
      .builder()
      .service(
        "/",
        service
          .decorate(LoggingService.newDecorator())
          .decorate(ThrottlingService.newDecorator(ThrottlingStrategy.always())))
      .build()
  }

  test("Should be able to decorate an HttpServiceRoute with a decorator") {
    val serviceWithRoutes = new HttpServiceWithRoutes {
      override def routes(): java.util.Set[Route] = Set(Route.ofCatchAll()).asJava

      override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = HttpResponse.of(200)
    }

    Server
      .builder()
      .service(
        serviceWithRoutes,
        LoggingService.newDecorator())
      .build()
  }

  test("Should be able to decorate a service with a lambda expression") {
    Server
      .builder()
      .service(
        "/",
        service
          .decorate((delegate, ctx, req) => delegate.serve(ctx, req))
          .decorate((delegate, ctx, req) => delegate.serve(ctx, req)))
      .build()
  }

  test("Should be able to configure an SslContext with a lambda expression") {
    Server
      .builder()
      .service("/", service)
      .tlsCustomizer { ctxBuilder =>
        ctxBuilder.sessionTimeout(86400)
      }
      .build()
  }

  test("Should be able to configure a VirtualHost with a lambda expression") {
    Server
      .builder()
      .withDefaultVirtualHost { vhostBuilder =>
        vhostBuilder.service("/", service)
      }
      .build()
  }

  test("Should be able to configure a Route with a lambda expression") {
    Server
      .builder()
      .withRoute { bindingBuilder =>
        bindingBuilder.get("/")
        bindingBuilder.matchesHeaders("a", value => value == "b")
        bindingBuilder.matchesParams("c", value => value == "d")
        bindingBuilder.build((_, _) => HttpResponse.of(200))
      }
      .build()
  }

  test("Should be able to configure a Route with a decorator") {
    Server
      .builder()
      .route()
      .get("/")
      .decorator(LoggingService.newDecorator())
      .decorators(LoggingService.newDecorator())
      .build(service)
      .build()
  }

  test("Should be able to configure access logger mapping with a lambda expression.") {
    Server
      .builder()
      .accessLogger { vhost => LoggerFactory.getLogger(vhost.defaultHostname) }
      .service("/", service)
      .build()
  }
}
