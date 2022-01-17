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

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.scala.{ClassTagExtensions, JavaTypeable}
import com.linecorp.armeria.client.ResponseAs
import com.linecorp.armeria.common.{HttpResponse, ResponseEntity}
import com.linecorp.armeria.internal.common.JacksonUtil
import com.linecorp.armeria.scala.implicits._
import java.io.File
import java.nio.file.Path
import scala.concurrent.Future

/**
 * A utility that asynchronously transforms a `HttpResponse` into another.
 */
object ScalaResponseAs {

  private val jsonMapper = JacksonUtil.newDefaultObjectMapper().asInstanceOf[JsonMapper]
  private val mapper = jsonMapper :: ClassTagExtensions

  /**
   * Aggregates an `HttpResponse` and convert the `AggregatedHttpResponse.content()` into bytes.
   */
  def bytes(): ResponseAs[HttpResponse, Future[ResponseEntity[Array[Byte]]]] =
    response => ResponseAs.bytes().as(response).toScala

  /**
   * Aggregates an `HttpResponse` and convert the `AggregatedHttpResponse.content()` into `String`.
   */
  def string(): ResponseAs[HttpResponse, Future[ResponseEntity[String]]] =
    response => ResponseAs.string().as(response).toScala

  /**
   * Aggregates an `HttpResponse` and deserialize the JSON `AggregatedHttpResponse.content()`
   * into the specified type object using the default `ObjectMapper`.
   */
  def json[A: JavaTypeable]: ResponseAs[HttpResponse, Future[ResponseEntity[A]]] = json(mapper)

  /**
   * Aggregates an `HttpResponse` and deserialize the JSON `AggregatedHttpResponse.content()`
   * into the specified type object using the specified Scala `ObjectMapper`.
   */
  def json[A: JavaTypeable](mapper: ClassTagExtensions): ResponseAs[HttpResponse, Future[ResponseEntity[A]]] =
    _.aggregate()
      .thenApply[ResponseEntity[A]] { agg =>
        val content = mapper.readValue(agg.content().array())
        ResponseEntity.of(agg.headers(), content, agg.trailers())
      }
      .toScala

  /**
   * Writes the content of an `HttpResponse` into the specified `Path`.
   */
  def path(path: Path): ResponseAs[HttpResponse, Future[ResponseEntity[Path]]] =
    ResponseAs.path(path).as(_).toScala

  /**
   * Writes the content of an `HttpResponse` into the specified `File`.
   */
  def file(file: File): ResponseAs[HttpResponse, Future[ResponseEntity[File]]] =
    ResponseAs
      .path(file.toPath)
      .as(_)
      .thenApply[ResponseEntity[File]](entity => ResponseEntity.of(entity.headers(), file, entity.trailers()))
      .toScala
}
