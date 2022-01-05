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

package com.linecorp.armeria.common

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.{ClassTagExtensions, JavaTypeable}
import com.linecorp.armeria.client.{ResponseAs, WebClient}
import com.linecorp.armeria.internal.common.JacksonUtil
import com.linecorp.armeria.scala.implicits._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object FutureResponseAs {

  private val jsonMapper = JacksonUtil.newDefaultObjectMapper().asInstanceOf[JsonMapper]
  private val mapper = jsonMapper :: ClassTagExtensions

  def bytes(): ResponseAs[HttpResponse, Future[ResponseEntity[Array[Byte]]]] =
    response => ResponseAs.bytes().as(response).toScala

  def string(): ResponseAs[HttpResponse, Future[ResponseEntity[String]]] =
    response => ResponseAs.string().as(response).toScala

  def json[A: JavaTypeable]: ResponseAs[HttpResponse, Future[ResponseEntity[A]]] =
    _.aggregate().thenApply { agg =>
      val content: A = mapper.readValue(agg.content().array())
      ResponseEntity.of(agg.headers(), content, agg.trailers())
    }.toScala
}

object Test {
  def main(args: Array[String]): Unit = {
    val client = WebClient.of()
    val response: Future[ResponseEntity[MyClass]] =
      client
        .prepare()
        .get("/foo")
        .as(FutureResponseAs.json[MyClass])
        .execute()
    response.map(x => x)
  }

  case class MyClass(id: String)
}
