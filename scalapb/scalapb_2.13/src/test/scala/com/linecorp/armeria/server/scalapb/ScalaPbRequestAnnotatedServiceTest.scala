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

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.{AggregatedHttpResponse, HttpMethod, HttpRequest, MediaType}
import com.linecorp.armeria.scalapb.testing.messages.SimpleRequest
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.annotation.{ConsumesJson, Post}
import com.linecorp.armeria.server.scalapb.ScalaPbRequestAnnotatedServiceTest.server
import com.linecorp.armeria.server.scalapb.ScalaPbRequestConverterFunctionTest.toJson
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.{AfterEach, BeforeEach, Test}
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import scalapb.json4s.Printer

class ScalaPbRequestAnnotatedServiceTest {

  private val printer = new Printer()

  private var client: WebClient = _

  @BeforeEach
  def setUp(): Unit = {
    server.start()
    client = WebClient.of(server.httpUri)
  }

  @AfterEach
  def tearDown(): Unit =
    server.stop()

  @Test
  def protobufRequest(): Unit = {
    val simpleRequest = SimpleRequest("Armeria")
    val response = client.post("/default-content-type", simpleRequest.toByteArray).aggregate.join
    assertThat(response.contentUtf8).isEqualTo("Hello, Armeria!")
  }

  @Test
  def jsonRequest(): Unit = {
    val simpleRequest = SimpleRequest("Armeria")
    val json = printer.print(simpleRequest)
    val request = HttpRequest.of(HttpMethod.POST, "/json", MediaType.JSON, json)
    val response = client.execute(request).aggregate.join
    assertThat(response.contentUtf8).isEqualTo("Hello, Armeria!")
  }

  @CsvSource(Array("/json+array", "/json+array2"))
  @ParameterizedTest
  def jsonArrayRequest(path: String): Unit = {
    val simpleRequest1 = SimpleRequest(size = 1)
    val simpleRequest2 = SimpleRequest(size = 2)
    val jsonArray = toJson(List(simpleRequest1, simpleRequest2))
    val request = HttpRequest.of(HttpMethod.POST, path, MediaType.JSON, jsonArray)
    val response = client.execute(request).aggregate.join
    assertThat(response.contentUtf8).isEqualTo("Sum: 3")
  }

  @CsvSource(Array("/json+object", "/json+object2"))
  @ParameterizedTest
  def jsonObjectRequest(path: String): Unit = {
    val simpleRequest1 = SimpleRequest(size = 1)
    val simpleRequest2 = SimpleRequest(size = 2)
    val jsonObject: String = toJson(Map("json1" -> simpleRequest1, "json2" -> simpleRequest2))
    val request: HttpRequest = HttpRequest.of(HttpMethod.POST, path, MediaType.JSON, jsonObject)
    val response: AggregatedHttpResponse = client.execute(request).aggregate.join
    assertThat(response.contentUtf8).isEqualTo("OK")
  }
}

object ScalaPbRequestAnnotatedServiceTest {

  val server = new ServerExtension() {
    override protected def configure(sb: ServerBuilder): Unit =
      // A workaround for 'ambiguous reference to overloaded definition' in Scala 2.12.x
      sb.annotatedService(new GreetingService(), Array.emptyObjectArray: _*)
  }

  private class GreetingService {
    @Post("/default-content-type")
    def noContentType(request: SimpleRequest): String = "Hello, Armeria!"

    @Post("/json")
    @ConsumesJson
    def consumeJson(request: SimpleRequest): String = "Hello, Armeria!"

    @Post("/json+array")
    @ConsumesJson
    def consumeJson(request: List[SimpleRequest]): String = s"Sum: ${request.map(_.size).sum}"

    @Post("/json+array2")
    @ConsumesJson
    def consumeJson2(request: Set[SimpleRequest]): String = s"Sum: ${request.map(_.size).sum}"

    @Post("/json+object")
    @ConsumesJson
    def consumeJson3(request: Map[String, SimpleRequest]): String = {
      assertThat(request("json1").size).isEqualTo(1)
      assertThat(request("json2").size).isEqualTo(2)
      "OK"
    }

    @Post("/json+object2")
    @ConsumesJson
    def consumeJson3(request: java.util.Map[String, SimpleRequest]): String = {
      assertThat(request.get("json1").size).isEqualTo(1)
      assertThat(request.get("json2").size).isEqualTo(2)
      "OK"
    }
  }
}
