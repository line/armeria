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
import com.linecorp.armeria.server.annotation._
import com.linecorp.armeria.server.logging.LoggingService
import com.linecorp.armeria.server.scalapb.ScalaPbResponseAnnotatedServiceTest.server
import com.linecorp.armeria.server.{ServerBuilder, ServiceRequestContext}
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.{AfterEach, BeforeEach, Test}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import _root_.scalapb.json4s.{JsonFormat, Parser}
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace
import testing.scalapb.messages._

import java.io.ByteArrayOutputStream
import java.util.stream.Stream
import scala.concurrent.{ExecutionContext, Future}

@GenerateNativeImageTrace
class ScalaPbResponseAnnotatedServiceTest {

  private val RECORD_SEPARATOR: Int = 0x1e
  private val LINE_FEED: Int = 0x0a

  private var client: WebClient = _
  private val parser: Parser = new Parser()

  @BeforeEach
  def setUp(): Unit = {
    server.start()
    client = WebClient
      .builder(ScalaPbResponseAnnotatedServiceTest.server.httpUri)
      .responseTimeoutMillis(0)
      .build()
  }

  @AfterEach
  def tearDown(): Unit = {
    server.stop
    ScalaPbResponseAnnotatedServiceTest.cause = None
  }

  @CsvSource(Array("/default-content-type", "/protobuf"))
  @ParameterizedTest
  def protobufResponse(path: String): Unit = {
    val response: AggregatedHttpResponse = client.get(path).aggregate.join
    assertThat(response.headers.contentType).isEqualTo(MediaType.PROTOBUF)
    val simpleResponse: SimpleResponse = SimpleResponse.parseFrom(response.content.array)
    assertThat(simpleResponse.message).isEqualTo("Hello, Armeria!")
  }

  @CsvSource(Array("/protobuf/future", "/protobuf/future+blocking"))
  @ParameterizedTest
  def protobufFutureResponse(path: String): Unit = {
    val response: AggregatedHttpResponse = client.get(path).aggregate.join
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
    assertThat(simpleResponse.message).isEqualTo("Hello, Armeria!")
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

  @Test
  def protobufOneOfResponse(): Unit = {
    val oneof = Add(Literal(1), Literal(2))
    val message = oneof.asMessage
    val res = client.post("/protobuf/oneof", message.toByteArray).aggregate().join()
    val actual = SimpleOneofMessage.parseFrom(res.content().array()).getAdd
    assertThat(actual).isEqualTo(oneof)
  }

  @Test
  def protobufJsonOneOfResponse(): Unit = {
    val oneof = Add(Literal(1), Literal(2))
    val json = ScalaPbConverterUtil.defaultJsonPrinter.print(oneof.asMessage)
    val res = client
      .prepare()
      .post("/protobuf+json/oneof")
      .content(MediaType.JSON_UTF_8, json)
      .execute()
      .aggregate()
      .join()
    val actual = JsonFormat.fromJsonString[SimpleOneofMessage](res.contentUtf8())
    assertThat(actual.getAdd).isEqualTo(oneof)
  }

  @Test
  def protobufJsonListOneOfResponse(): Unit = {
    val oneofs = List(Add(Literal(1), Literal(2)), Add(Literal(3), Literal(4)))

    val jsonArray = oneofs
      .map(add => ScalaPbConverterUtil.defaultJsonPrinter.print(add.asMessage))
      .mkString("[", ",", "]")
    val res = client
      .prepare()
      .post("/protobuf+json/oneofs")
      .content(MediaType.JSON_UTF_8, jsonArray)
      .execute()
      .aggregate()
      .join()

    assertThatJson(res.contentUtf8()).isEqualTo(jsonArray)
  }

  @CsvSource(Array("/protobuf+json-seq/stream", "/protobuf+json-seq/publisher", "/protobuf+json-seq/unary"))
  @ParameterizedTest
  def protobufJsonSeqResponse(path: String): Unit = {
    val response = client.get(path).aggregate.join
    val mediaType = response.headers.contentType
    assertThat(mediaType.is(MediaType.JSON_SEQ)).isTrue

    val out = new ByteArrayOutputStream
    out.write(RECORD_SEPARATOR)
    out.write("""{"message":"Hello, Armeria1!","status":0}""".getBytes)
    out.write(LINE_FEED)

    if (path != "/protobuf+json-seq/unary") {
      out.write(RECORD_SEPARATOR)
      out.write("""{"message":"Hello, Armeria2!","status":0}""".getBytes)
      out.write(LINE_FEED)
    }

    assertThat(response.content.array).isEqualTo(out.toByteArray)
  }
}

object ScalaPbResponseAnnotatedServiceTest {
  @RegisterExtension
  val server: ServerExtension = new ServerExtension() {
    override protected def configure(sb: ServerBuilder): Unit = {
      // A workaround for 'ambiguous reference to overloaded definition' in Scala 2.12.x
      sb.annotatedService(new GreetingService(), Array.emptyObjectArray: _*)
      sb.decorator(LoggingService.newDecorator())
    }
  }

  private var cause: Option[Throwable] = None

  @ExceptionHandler(classOf[CustomExceptionHandlerFunction])
  class GreetingService {
    @Get("/default-content-type")
    def noContentType: SimpleResponse = SimpleResponse("Hello, Armeria!")

    @Get("/protobuf")
    @ProducesProtobuf
    def produceProtobuf: SimpleResponse = SimpleResponse("Hello, Armeria!")

    @Get("/protobuf/future")
    @ProducesProtobuf
    def produceProtobuf(implicit ec: ExecutionContext): Future[SimpleResponse] =
      Future {
        ServiceRequestContext.current()
        SimpleResponse("Hello, Armeria!")
      }

    @Blocking
    @Get("/protobuf/future+blocking")
    @ProducesProtobuf
    def blockingProduceProtobuf(implicit ec: ExecutionContext): Future[SimpleResponse] =
      Future {
        ServiceRequestContext.current()
        assertThat(Thread.currentThread().getName).contains("blocking-tasks")
        SimpleResponse("Hello, Armeria!")
      }

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

    @Get("/protobuf+json-seq/publisher")
    @ProducesJsonSequences
    def protobufJsonSeqPublisher: Publisher[SimpleResponse] =
      Flux.just(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf+json-seq/stream")
    @ProducesJsonSequences
    def protobufJsonSeqStream: Stream[SimpleResponse] =
      Stream.of(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf+json-seq/unary")
    @ProducesJsonSequences
    def protobufJsonSeqUnary: SimpleResponse = SimpleResponse("Hello, Armeria1!")

    @Get("/protobuf/stream")
    @Produces(MediaTypeNames.PROTOBUF)
    def protobufStream: Stream[SimpleResponse] =
      Stream.of(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Get("/protobuf/publisher")
    @Produces(MediaTypeNames.PROTOBUF)
    def protobufPublisher: Publisher[SimpleResponse] =
      Flux.just(SimpleResponse("Hello, Armeria1!"), SimpleResponse("Hello, Armeria2!"))

    @Post("/protobuf+json/oneof")
    @ProducesJson
    def protobufJsonOneOf(oneof: SimpleOneof): SimpleOneof =
      oneof

    @Post("/protobuf/oneof")
    @Produces(MediaTypeNames.PROTOBUF)
    def protobufOneOf(oneof: SimpleOneof): SimpleOneof =
      oneof

    @Post("/protobuf+json/oneofs")
    @ProducesJson
    def protobufJsonOneOfList(oneofs: List[SimpleOneof]): List[SimpleOneof] =
      oneofs
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
