/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.scala

import com.google.common.collect.Iterables
import com.linecorp.armeria.common.{Cookie, HttpResponse, ResponseEntity}
import com.linecorp.armeria.scala.implicits._
import com.linecorp.armeria.server.{ServerBuilder, ServerSuite}
import munit.FunSuite
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class RestClientSuite extends FunSuite with ServerSuite {
  override protected def configureServer: ServerBuilder => Unit = { sb =>
    sb.service(
      "/rest/{id}",
      (ctx, req) => {
        req
          .aggregate()
          .thenApply[HttpResponse](agg => {
            HttpResponse.ofJson(RestResponse(ctx.pathParam("id"), agg.contentUtf8()))
          })
          .toHttpResponse
      }
    ).service(
      "/rest/complex/{id}",
      (ctx, req) => {
        req
          .aggregate()
          .thenApply[HttpResponse](agg => {
            HttpResponse.ofJson(ComplexResponse(
              id = ctx.pathParam("id"),
              method = ctx.method().toString,
              query = ctx.queryParam("query"),
              header = req.headers.get("x-header"),
              trailer = agg.trailers().get("x-trailer"),
              cookie = Iterables.getFirst(req.headers.cookies, null).value(),
              content = agg.contentUtf8()
            ))
          })
          .toHttpResponse
      }
    )
  }

  test("should create ScalaRestClient with implicit class") {
    val restClient = server.webClient().toScalaRestClient()
    val future: Future[ResponseEntity[RestResponse]] =
      restClient
        .get("/rest/{id}")
        .pathParam("id", 1)
        .execute[RestResponse]()
    val content = Await.result(future, Duration.Inf).content()
    assertEquals(content.id, "1")
  }

  test("should create ScalaRestClient with factory method") {
    val restClient = ScalaRestClient(server.webClient())
    val future: Future[ResponseEntity[RestResponse]] =
      restClient
        .get("/rest/{id}")
        .pathParam("id", 1)
        .execute[RestResponse]()
    val content = Await.result(future, Duration.Inf).content()
    assertEquals(content.id, "1")
  }

  test("complex input") {
    val restClient = server.webClient().toScalaRestClient()
    val future =
      restClient
        .post("/rest/complex/{id}")
        .content("content")
        .headers(Map("x-header" -> "header-value"))
        .trailers(Map("x-trailer" -> "trailer-value"))
        .cookies(List(Cookie.ofSecure("cookie", "cookie-value")))
        .pathParams(Map("id" -> "1"))
        .queryParams(Map("query" -> "query-value"))
        .execute[ComplexResponse]()

    val content = Await.result(future, Duration.Inf).content()
    assertEquals(content.id, "1")
    assertEquals(content.method, "POST")
    assertEquals(content.query, "query-value")
    assertEquals(content.header, "header-value")
    assertEquals(content.trailer, "trailer-value")
    assertEquals(content.cookie, "cookie-value")
    assertEquals(content.content, "content")
  }
}

case class RestResponse(id: String, content: String)

case class ComplexResponse(
    id: String,
    method: String,
    query: String,
    header: String,
    trailer: String,
    cookie: String,
    content: String);
