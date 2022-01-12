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

package com.linecorp.armeria.client

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.{ClassTagExtensions, JavaTypeable}
import com.linecorp.armeria.client.ScalaResponseAs.json
import com.linecorp.armeria.common.{HttpResponse, ResponseEntity}
import com.linecorp.armeria.internal.common.JacksonUtil
import com.linecorp.armeria.scala.implicits._
import com.linecorp.armeria.server.{ServerBuilder, ServerSuite}
import munit.FunSuite
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class ScalaResponseAsSuite extends FunSuite with ServerSuite {
  override protected def configureServer: ServerBuilder => Unit = { sb =>
    sb.service("/foo", (_, _) => HttpResponse.ofJson(MyClass("hello")))
  }

  test("should be able to convert a response into a case class") {
    val client = WebClient.of(server.httpUri())
    val response: Future[MyClass] =
      client
        .prepare()
        .get("/foo")
        .as(json[MyClass])
        .execute()
        .map(_.content())

    assertEquals(Await.result(response, Duration.Inf), MyClass("hello"))
  }
}

object ScalaResponseAs {

  private val jsonMapper = JacksonUtil.newDefaultObjectMapper().asInstanceOf[JsonMapper]
  private val mapper = jsonMapper :: ClassTagExtensions

  def bytes(): ResponseAs[HttpResponse, Future[ResponseEntity[Array[Byte]]]] =
    response => ResponseAs.bytes().as(response).toScala

  def string(): ResponseAs[HttpResponse, Future[ResponseEntity[String]]] =
    response => ResponseAs.string().as(response).toScala

  def json[A: JavaTypeable]: ResponseAs[HttpResponse, Future[ResponseEntity[A]]] =
    _.aggregate()
      .thenApply[ResponseEntity[A]] { agg =>
        val content: A = mapper.readValue(agg.content().array())
        ResponseEntity.of(agg.headers(), content, agg.trailers())
      }
      .toScala
}

case class MyClass(id: String)
