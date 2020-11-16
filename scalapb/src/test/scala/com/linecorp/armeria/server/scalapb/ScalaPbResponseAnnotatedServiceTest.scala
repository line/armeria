/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.scalapb

import com.google.common.collect.{ImmutableList, ImmutableMap, ImmutableSet}
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common._
import com.linecorp.armeria.scalapb.testing.messages.SimpleResponse
import com.linecorp.armeria.server.annotation._
import com.linecorp.armeria.server.scalapb.ScalaPbResponseAnnotatedServiceTest.server
import com.linecorp.armeria.server.{ServerBuilder, ServiceRequestContext}
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import java.util.stream.Stream
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.{AfterEach, BeforeEach, Test}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import scalapb.json4s.Parser

class ScalaPbResponseAnnotatedServiceTest {

  private var client: WebClient = _
  private var parser: Parser = new Parser()

  @BeforeEach
  private def setUp(): Unit = {
    server.start()
    client = WebClient.of(ScalaPbResponseAnnotatedServiceTest.server.httpUri)
  }

  @AfterEach
  private def tearDown(): Unit = {
    server.stop
    ScalaPbResponseAnnotatedServiceTest.cause = None
  }

  @Test
  def protobufResponse(): Unit = {
    val response: AggregatedHttpResponse = client.get("/default-content-type").aggregate.join
    assertThat(response.headers.contentType).isEqualTo(MediaType.PROTOBUF)
    val simpleResponse: SimpleResponse = SimpleResponse.parseFrom(response.content.array)
    assertThat(simpleResponse.message).isEqualTo("Hello, Armeria!")
  }

  @CsvSource(Array("json", "protobuf+json"))
  @ParameterizedTest
  def protobufJsonResponse(contentType: String): Unit = {
    val response: AggregatedHttpResponse = client.get(s"/$contentType").aggregate.join
    assertThat(response.headers.contentType.subtype).isEqualTo(contentType)
    val simpleResponse = parser.fromJsonString[SimpleResponse](response.contentUtf8())
    Assertions.assertThat(simpleResponse.message).isEqualTo("Hello, Armeria!")
  }

  @CsvSource(Array("/protobuf/stream", "/protobuf/publisher"))
  @ParameterizedTest
  def protobufStreamResponse(path: String): Unit = {
    val response: AggregatedHttpResponse = client.get(path).aggregate.join
    assertThat(response.status.code()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.code())
    assertThat(ScalaPbResponseAnnotatedServiceTest.cause.get)
      .isInstanceOf(classOf[IllegalArgumentException])
  }

  @Test
  def protobufJsonPublisherResponse(): Unit = {
    val response: AggregatedHttpResponse = client.get("/protobuf+json/publisher").aggregate.join
    val mediaType: MediaType = response.headers.contentType
    assertThat(mediaType.is(MediaType.JSON)).isTrue
    val expected: String =
      """[
        |  {"message":"Hello, Armeria1!", "status":0},
        |  {"message":"Hello, Armeria2!", "status":0}
        |]""".stripMargin
    assertThatJson(response.contentUtf8).isEqualTo(expected)
  }

  @CsvSource(Array("stream", "list", "vector", "set", "jlist", "jset"))
  @ParameterizedTest
  def protobufJsonMultiResponse(collection: String): Unit = {
    val response: AggregatedHttpResponse = client.get(s"/protobuf+json/$collection").aggregate.join
    val mediaType: MediaType = response.headers.contentType
    assertThat(mediaType.subtype).isEqualTo("protobuf+json")
    val expected: String =
      """[
        |  {"message":"Hello, Armeria1!", "status":0},
        |  {"message":"Hello, Armeria2!", "status":0}
        |]""".stripMargin
    assertThatJson(response.contentUtf8).isEqualTo(expected)
  }

  @CsvSource(Array("map", "jmap"))
  @ParameterizedTest
  def protobufJsonObjectResponse(collection: String): Unit = {
    val response: AggregatedHttpResponse = client.get(s"/protobuf+json/$collection").aggregate.join
    val mediaType: MediaType = response.headers.contentType
    assertThat(mediaType.subtype).isEqualTo("protobuf+json")
    val expected: String =
      """{
        |  "json1": {"message":"Hello, Armeria1!", "status":0},
        |  "json2": {"message":"Hello, Armeria2!", "status":0}
        |}""".stripMargin
    assertThatJson(response.contentUtf8).isEqualTo(expected)
  }
}

object ScalaPbResponseAnnotatedServiceTest {
  @RegisterExtension
  val server: ServerExtension = new ServerExtension() {
    override protected def configure(sb: ServerBuilder): Unit =
      // A workaround for 'ambiguous reference to overloaded definition' in Scala 2.12.x
      sb.annotatedService(new ScalaPbResponseAnnotatedServiceTest.GreetingService(), Array.emptyObjectArray: _*)
  }

  private var cause: Option[Throwable] = None

  @ExceptionHandler(classOf[CustomExceptionHandlerFunction])
  class GreetingService {
    @Get("/default-content-type")
    def noContentType: SimpleResponse = SimpleResponse("Hello, Armeria!")

    @Get("/json")
    @ProducesJson
    def produceJson: SimpleResponse = SimpleResponse("Hello, Armeria!")

    @Get("/protobuf+json")
    @Produces("application/protobuf+json")
    def protobufJson: SimpleResponse = SimpleResponse("Hello, Armeria!")

    @Get("/protobuf+json/publisher")
    @ProducesJson
    def protobufJsonPublisher: Publisher[SimpleResponse] =
      Flux.just(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf+json/stream")
    @Produces("application/protobuf+json")
    def protobufJsonStream: Stream[SimpleResponse] =
      Stream.of(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf+json/list")
    @Produces("application/protobuf+json")
    def protobufJsonList: List[SimpleResponse] =
      List(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf+json/vector")
    @Produces("application/protobuf+json")
    def protobufJsonVector: Vector[SimpleResponse] =
      Vector(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf+json/set")
    @Produces("application/protobuf+json")
    def protobufJsonSet: Set[SimpleResponse] =
      Set(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf+json/jlist")
    @Produces("application/protobuf+json")
    def protobufJsonJavaList: java.util.List[SimpleResponse] =
      ImmutableList.of(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf+json/jset")
    @Produces("application/protobuf+json")
    def protobufJsonJavaSet: java.util.Set[SimpleResponse] =
      ImmutableSet.of(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf+json/map")
    @Produces("application/protobuf+json")
    def protobufJsonMap: Map[String, SimpleResponse] =
      Map("json1" -> SimpleResponse("Hello, Armeria1!"), "json2" -> SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf+json/jmap")
    @Produces("application/protobuf+json")
    def protobufJsonJavaMap: java.util.Map[String, SimpleResponse] =
      ImmutableMap.of("json1", SimpleResponse("Hello, Armeria1!"), "json2", SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf/stream")
    @Produces(MediaTypeNames.PROTOBUF)
    def protobufStream: Stream[SimpleResponse] =
      Stream.of(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf/publisher")
    @Produces(MediaTypeNames.PROTOBUF)
    def protobufPublisher: Publisher[SimpleResponse] =
      Flux.just(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))
  }

  private class CustomExceptionHandlerFunction extends ExceptionHandlerFunction {
    override def handleException(
        ctx: ServiceRequestContext,
        req: HttpRequest,
        cause: Throwable): HttpResponse = {
      ScalaPbResponseAnnotatedServiceTest.cause = Some(cause)
      HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR)
    }
  }
}
