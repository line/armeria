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

import com.linecorp.armeria.client._
import com.linecorp.armeria.client.endpoint.EndpointGroup
import com.linecorp.armeria.common.annotation.UnstableApi
import com.linecorp.armeria.common.util.Unwrappable
import com.linecorp.armeria.common.{HttpMethod, Scheme}

import java.net.URI
import java.util.Objects.requireNonNull

/**
 * A client designed for calling [[https://restfulapi.net/ RESTful APIs]] conveniently.
 */
@UnstableApi
trait ScalaRestClient extends ClientBuilderParams with Unwrappable {

  /**
   * Sets the `path` with `HttpMethod.GET` and returns a fluent request builder.
   * {{{
   * val restClient: ScalaRestClient = ScalaRestClient("https://rest.example.com")
   * val response: Future[ResponseEntity[Customer]] =
   *   restClient.get("/api/v1/customers/{customerId}")
   *             .pathParam("customerId", "0000001")
   *             .execute[Customer]()
   * }}}
   */
  def get(pathPattern: String): ScalaRestClientPreparation = path(HttpMethod.GET, pathPattern)

  /**
   * Sets the `path` with `HttpMethod.POST` and returns a fluent request builder.
   * {{{
   * val restClient: ScalaRestClient = ScalaRestClient("https://rest.example.com")
   * val response: Future[ResponseEntity[Result]] =
   *   restClient.post("/api/v1/customers")
   *             .contentJson(new Customer(...))
   *             .execute[Result]()
   * }}}
   */
  def post(pathPattern: String): ScalaRestClientPreparation = path(HttpMethod.POST, pathPattern)

  /**
   * Sets the `path` with `HttpMethod.PUT` and returns a fluent request builder.
   * {{{
   * val restClient: ScalaRestClient = ScalaRestClient("https://rest.example.com")
   * val response: Future[ResponseEntity[Result]] =
   *   restClient.put("/api/v1/customers")
   *             .contentJson(new Customer(...))
   *             .execute[Result]()
   * }}}
   */
  def put(pathPattern: String): ScalaRestClientPreparation = path(HttpMethod.PUT, pathPattern)

  /**
   * Sets the `path` with `HttpMethod.PATCH` and returns a fluent request builder.
   * {{{
   * val restClient: ScalaRestClient = ScalaRestClient("https://rest.example.com")
   * val response: Future[ResponseEntity[Result]] =
   *   restClient.patch("/api/v1/customers")
   *             .contentJson(new Customer(...))
   *             .execute[Result]()
   * }}}
   */
  def patch(pathPattern: String): ScalaRestClientPreparation = path(HttpMethod.PATCH, pathPattern)

  /**
   * Sets the `path` an `HttpMethod.DELETE` and returns a fluent request builder.
   * {{{
   * val restClient: ScalaRestClient = ScalaRestClient("https://rest.example.com")
   * val response: Future[ResponseEntity[Result]] =
   *   restClient.delete("/api/v1/customers")
   *             .contentJson(new Customer(...))
   *             .execute[Result]()
   * }}}
   */
  def delete(pathPattern: String): ScalaRestClientPreparation = path(HttpMethod.DELETE, pathPattern)

  /**
   * Sets the `HttpMethod` and the `path` and returns a fluent request builder.
   * {{{
   * val restClient: ScalaRestClient = ScalaRestClient("https://rest.example.com")
   * val response: Future[ResponseEntity[Customer]] =
   *   restClient.path(HttpMethod.GET, "/api/v1/customers/{customerId}")
   *             .pathParam("customerId", "0000001")
   *             .execute[Customer]()
   * }}}
   */
  def path(method: HttpMethod, path: String): ScalaRestClientPreparation
}

/**
 * A [[https://restfulapi.net/ REST]] client for Scala.
 * If you want to create a `ScalaRestClient` with various options, create a `WebClient` first
 * and convert it into a `ScalaRestClient` via `WebClient.asScalaRestClient()` or `ScalaRestClient(WebClient)`.
 * {{{
 * val webClient: WebClient =
 *   WebClient.builder("https://api.example.com")
 *     .responseTimeout(Duration.ofSeconds(10))
 *     .decorator(LoggingClient.newDecorator())
 *     ...
 *     .build()
 *
 * // Use the extension method
 * import com.linecorp.armeria.scala.implicits._
 * val restClient: ScalaRestClient = webClient.asScalaRestClient()
 *
 * // Or explicitly call the factory method
 * val restClient: ScalaRestClient = ScalaRestClient(webClient)
 * }}}
 */
@UnstableApi
object ScalaRestClient {

  private val DEFAULT = new DefaultScalaRestClient(RestClient.of())

  /**
   * Returns a `ScalaRestClient` without a base URI using the default `ClientFactory` and
   * the default `ClientOptions`.
   */
  def apply(): ScalaRestClient = DEFAULT

  /**
   * Returns a new `ScalaRestClient` that connects to the specified `uri` using the default options.
   *
   * @param uri the URI of the server endpoint
   * @throws IllegalArgumentException if the `uri` is not valid or its scheme is not one of the values
   *                                  in `SessionProtocol.httpValues()` or `SessionProtocol.httpsValues()`.
   */
  def apply(uri: String): ScalaRestClient = apply(WebClient.of(uri))

  /**
   * Returns a new `ScalaRestClient` that connects to the specified `URI` using the default options.
   *
   * @param uri the `URI` of the server endpoint
   * @throws IllegalArgumentException if the `uri` is not valid or its scheme is not one of the values
   *                                  in `SessionProtocol.httpValues()` or `SessionProtocol.httpsValues()`.
   */
  def apply(uri: URI): ScalaRestClient = apply(WebClient.of(uri))

  /**
   * Returns a new `ScalaRestClient` with the specified `WebClient`.
   */
  def apply(webClient: WebClient): ScalaRestClient =
    if (webClient eq WebClient.of()) {
      DEFAULT
    } else {
      new DefaultScalaRestClient(RestClient.of(webClient))
    }
}

private final class DefaultScalaRestClient(delegate: RestClient) extends ScalaRestClient {

  override def path(method: HttpMethod, path: String): ScalaRestClientPreparation = {
    requireNonNull(method, "method")
    requireNonNull(path, "path")
    new ScalaRestClientPreparation(delegate.path(method, path))
  }

  override def scheme(): Scheme = delegate.scheme()

  override def endpointGroup(): EndpointGroup = delegate.endpointGroup()

  override def absolutePathRef(): String = delegate.absolutePathRef()

  override def uri(): URI = delegate.uri()

  override def clientType(): Class[_] = classOf[ScalaRestClient]

  override def options(): ClientOptions = delegate.options()

  override def unwrap(): HttpClient = delegate.unwrap().asInstanceOf[HttpClient]

  override def unwrapAll(): Object = delegate.unwrapAll();
}
