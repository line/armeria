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

import com.fasterxml.jackson.module.scala.{ClassTagExtensions, JavaTypeable}
import com.linecorp.armeria.client.{
  RequestOptions,
  RequestPreparationSetters,
  ResponseAs,
  RestClientPreparation
}
import com.linecorp.armeria.common.annotation.UnstableApi
import com.linecorp.armeria.common.{Cookie, ExchangeType, HttpData, HttpResponse, MediaType, ResponseEntity}
import com.linecorp.armeria.scala.implicits._
import io.netty.util.AttributeKey
import java.lang.{Iterable => JIterable}
import java.time.Duration
import java.util.{Map => JMap}
import org.reactivestreams.Publisher
import scala.collection.immutable
import scala.concurrent.Future

/**
 * Prepares and executes a new `HttpRequest` for `ScalaRestClient`.
 */
@UnstableApi
final class ScalaRestClientPreparation private[scala] (delegate: RestClientPreparation)
    extends RequestPreparationSetters {

  /**
   * Sends the HTTP request and converts the JSON response body as the `A` object using the default
   * `ClassTagExtensions`.
   */
  def execute[A: JavaTypeable](): Future[ResponseEntity[A]] =
    delegate.execute[Future[ResponseEntity[A]]](ScalaResponseAs.json[A])

  /**
   * Sends the HTTP request and converts the JSON response body as the `A` object using the specified
   * `ClassTagExtensions`.
   */
  def execute[A: JavaTypeable](mapper: ClassTagExtensions): Future[ResponseEntity[A]] =
    delegate.execute(ScalaResponseAs.json[A](mapper))

  /**
   * Sends the HTTP request and converts the `HttpResponse` using the `ResponseAs`.
   * `ScalaResponseAs` provides converters for well-known types.
   */
  def execute[T](responseAs: ResponseAs[HttpResponse, T]): T = {
    delegate.execute(responseAs)
  }

  override def requestOptions(requestOptions: RequestOptions): ScalaRestClientPreparation = {
    delegate.requestOptions(requestOptions)
    this
  }

  override def pathParam(name: String, value: Any): ScalaRestClientPreparation = {
    delegate.pathParam(name, value)
    this
  }

  override def pathParams(pathParams: JMap[String, _]): ScalaRestClientPreparation = {
    delegate.pathParams(pathParams)
    this
  }

  /**
   * Sets multiple path params for this request.
   */
  def pathParams(pathParams: Map[String, Any]): ScalaRestClientPreparation = {
    delegate.pathParams(pathParams.asJava)
    this
  }

  override def disablePathParams(): ScalaRestClientPreparation = {
    delegate.disablePathParams()
    this
  }

  override def queryParam(name: String, value: Any): ScalaRestClientPreparation = {
    delegate.queryParam(name, value)
    this
  }

  override def queryParams(
      queryParams: JIterable[_ <: JMap.Entry[_ <: String, String]]): ScalaRestClientPreparation = {
    delegate.queryParams(queryParams)
    this
  }

  /**
   * Sets multiple query params for this request.
   */
  def queryParams(queryParams: Map[String, String]): ScalaRestClientPreparation = {
    queryParams.foreach { case (k, v) => queryParam(k, v) }
    this
  }

  override def responseTimeout(responseTimeout: Duration): ScalaRestClientPreparation = {
    delegate.responseTimeout(responseTimeout)
    this
  }

  override def responseTimeoutMillis(responseTimeoutMillis: Long): ScalaRestClientPreparation = {
    delegate.responseTimeoutMillis(responseTimeoutMillis)
    this
  }

  override def writeTimeout(writeTimeout: Duration): ScalaRestClientPreparation = {
    delegate.writeTimeout(writeTimeout)
    this
  }

  override def writeTimeoutMillis(writeTimeoutMillis: Long): ScalaRestClientPreparation = {
    delegate.writeTimeoutMillis(writeTimeoutMillis)
    this
  }

  override def maxResponseLength(maxResponseLength: Long): ScalaRestClientPreparation = {
    delegate.maxResponseLength(maxResponseLength)
    this
  }

  override def requestAutoAbortDelay(delay: Duration): ScalaRestClientPreparation = {
    delegate.requestAutoAbortDelay(delay)
    this
  }

  override def requestAutoAbortDelayMillis(delayMillis: Long): ScalaRestClientPreparation = {
    delegate.requestAutoAbortDelayMillis(delayMillis)
    this
  }

  override def attr[V](key: AttributeKey[V], value: V): ScalaRestClientPreparation = {
    delegate.attr(key, value)
    this
  }

  override def exchangeType(exchangeType: ExchangeType): ScalaRestClientPreparation = {
    delegate.exchangeType(exchangeType)
    this
  }

  override def content(content: String): ScalaRestClientPreparation = {
    delegate.content(content)
    this
  }

  override def content(contentType: MediaType, content: CharSequence): ScalaRestClientPreparation = {
    delegate.content(contentType, content)
    this
  }

  override def content(contentType: MediaType, content: String): ScalaRestClientPreparation = {
    delegate.content(contentType, content)
    this
  }

  override def content(format: String, content: Object*): ScalaRestClientPreparation = {
    delegate.content(format, content)
    this
  }

  override def content(contentType: MediaType, format: String, content: Object*): ScalaRestClientPreparation = {
    delegate.content(contentType, format, content)
    this
  }

  override def content(contentType: MediaType, content: Array[Byte]): ScalaRestClientPreparation = {
    delegate.content(contentType, content)
    this
  }

  override def content(contentType: MediaType, content: HttpData): ScalaRestClientPreparation = {
    delegate.content(contentType, content)
    this
  }

  override def content(content: Publisher[_ <: HttpData]): ScalaRestClientPreparation = {
    delegate.content(content)
    this
  }

  override def content(
      contentType: MediaType,
      content: Publisher[_ <: HttpData]): ScalaRestClientPreparation = {
    delegate.content(contentType, content)
    this
  }

  override def contentJson(content: AnyRef): ScalaRestClientPreparation = {
    delegate.contentJson(content)
    this
  }

  override def header(name: CharSequence, value: Any): ScalaRestClientPreparation = {
    delegate.header(name, value)
    this
  }

  override def headers(
      headers: JIterable[_ <: JMap.Entry[_ <: CharSequence, String]]): ScalaRestClientPreparation = {
    delegate.headers(headers)
    this
  }

  /**
   * Adds multiple headers for this request.
   */
  def headers(headers: Map[_ <: CharSequence, String]): ScalaRestClientPreparation = {
    headers.foreach { case (k, v) => header(k, v) }
    this
  }

  override def trailer(name: CharSequence, value: Any): ScalaRestClientPreparation = {
    delegate.trailer(name, value)
    this
  }

  override def trailers(
      trailers: JIterable[_ <: JMap.Entry[_ <: CharSequence, String]]): ScalaRestClientPreparation = {
    delegate.trailers(trailers)
    this
  }

  /**
   * Adds multiple trailers for this request.
   */
  def trailers(trailers: Map[_ <: CharSequence, String]): ScalaRestClientPreparation = {
    trailers.foreach { case (k, v) => trailer(k, v) }
    this
  }

  override def cookie(cookie: Cookie): ScalaRestClientPreparation = {
    delegate.cookie(cookie)
    this
  }

  override def cookies(cookies: JIterable[_ <: Cookie]): ScalaRestClientPreparation = {
    delegate.cookies(cookies)
    this
  }

  /**
   * Adds multiple `Cookie`s for this request.
   */
  def cookies(cookies: immutable.Seq[Cookie]): ScalaRestClientPreparation = {
    delegate.cookies(cookies.asJava)
    this
  }
}
