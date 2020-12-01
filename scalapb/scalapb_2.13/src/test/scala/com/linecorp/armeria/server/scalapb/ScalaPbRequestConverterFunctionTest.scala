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
import com.google.common.reflect.TypeToken
import com.linecorp.armeria.common.{AggregatedHttpRequest, HttpData, HttpMethod, HttpRequest, MediaType}
import com.linecorp.armeria.scalapb.testing.messages.SimpleRequest
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.FallthroughException
import com.linecorp.armeria.server.scalapb.ScalaPbRequestConverterFunctionTest._
import java.lang.reflect.ParameterizedType
import org.assertj.core.api.Assertions.{assertThat, assertThatThrownBy}
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.`extension`.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.{Arguments, ArgumentsProvider, ArgumentsSource}
import scala.collection.mutable.ArrayBuffer
import scalapb.GeneratedMessage
import scalapb.json4s.Printer

class ScalaPbRequestConverterFunctionTest {

  @Test
  def protobufToMessage(): Unit = {
    val converter = ScalaPbRequestConverterFunction()
    val req = AggregatedHttpRequest.of(ctx.request.headers, HttpData.wrap(simpleRequest1.toByteArray))
    val requestObject = converter.convertRequest(ctx, req, classOf[SimpleRequest], null)
    assertThat(requestObject).isEqualTo(simpleRequest1)
  }

  @Test
  def failProtobufToCollection(): Unit = {
    val typeToken = new TypeToken[List[SimpleRequest]]() {}
    val converter = ScalaPbRequestConverterFunction()
    val req = AggregatedHttpRequest.of(ctx.request.headers, HttpData.wrap(simpleRequest1.toByteArray))
    assertThatThrownBy(() =>
      converter.convertRequest(ctx, req, classOf[List[_]], typeToken.getType.asInstanceOf[ParameterizedType]))
      .isInstanceOf(classOf[FallthroughException])
  }

  @Test
  def jsonToMessage(): Unit = {
    val converter = ScalaPbRequestConverterFunction()
    val req = AggregatedHttpRequest.of(
      ctx.request.headers.withMutations(builder => builder.contentType(MediaType.JSON)),
      HttpData.ofUtf8(printer.print(simpleRequest1)))
    val requestObject = converter.convertRequest(ctx, req, classOf[SimpleRequest], null)
    assertThat(requestObject).isEqualTo(simpleRequest1)
  }

  @ArgumentsSource(classOf[JsonArrayRequestProvider])
  @ParameterizedTest
  def jsonArrayToCollection(collection: Any, json: String, typeToken: TypeToken[_]): Unit = {
    val converter = ScalaPbRequestConverterFunction()
    val req = AggregatedHttpRequest.of(
      ctx.request.headers
        .withMutations(builder => builder.contentType(MediaType.JSON)),
      HttpData.ofUtf8(json))
    val requestObject =
      converter.convertRequest(
        ctx,
        req,
        typeToken.getRawType,
        typeToken.getType.asInstanceOf[ParameterizedType])
    assertThat(requestObject).isEqualTo(collection)
  }

  @ArgumentsSource(classOf[JsonObjectRequestProvider])
  @ParameterizedTest
  def jsonObjectToMap(map: Any, json: String, typeToken: TypeToken[_]): Unit = {
    val converter = ScalaPbRequestConverterFunction()
    val req = AggregatedHttpRequest.of(
      ctx.request.headers
        .withMutations(builder => builder.contentType(MediaType.JSON)),
      HttpData.ofUtf8(json))
    val requestObject =
      converter.convertRequest(
        ctx,
        req,
        typeToken.getRawType,
        typeToken.getType.asInstanceOf[ParameterizedType])
    assertThat(requestObject).isEqualTo(map)
  }

  @ArgumentsSource(classOf[JsonArrayRequestProvider])
  @ParameterizedTest
  def jsonArrayWithNoContentType(collection: Any, json: String, typeToken: TypeToken[_]): Unit = {
    val converter = ScalaPbRequestConverterFunction()
    val req = AggregatedHttpRequest.of(ctx.request.headers, HttpData.ofUtf8(json))
    assertThatThrownBy { () =>
      converter.convertRequest(
        ctx,
        req,
        typeToken.getRawType,
        typeToken.getType.asInstanceOf[ParameterizedType])
    }.isInstanceOf(classOf[FallthroughException])
  }
}

private[scalapb] object ScalaPbRequestConverterFunctionTest {

  private val ctx: ServiceRequestContext = ServiceRequestContext.of(HttpRequest.of(HttpMethod.POST, "/"))

  private val simpleRequest1: SimpleRequest = SimpleRequest("Armeria")
  private val simpleRequest2: SimpleRequest = SimpleRequest("Protobuf")
  private val printer: Printer = new Printer()

  private class JsonArrayRequestProvider extends ArgumentsProvider {
    override def provideArguments(context: ExtensionContext): java.util.stream.Stream[Arguments] = {
      val list = List(simpleRequest1, simpleRequest2)
      val vector = list.toVector
      val set = list.toSet
      val jlist = ImmutableList.of(simpleRequest1, simpleRequest2);
      val jset = ImmutableSet.of(simpleRequest1, simpleRequest2);

      java.util.stream.Stream.of(
        Arguments.of(list, toJson(list), new TypeToken[List[SimpleRequest]]() {}),
        Arguments.of(vector, toJson(vector), new TypeToken[Vector[SimpleRequest]]() {}),
        Arguments.of(set, toJson(set), new TypeToken[Set[SimpleRequest]]() {}),
        Arguments.of(jlist, toJson(jlist), new TypeToken[java.util.List[SimpleRequest]]() {}),
        Arguments.of(jset, toJson(jset), new TypeToken[java.util.Set[SimpleRequest]]() {})
      )
    }
  }

  private class JsonObjectRequestProvider extends ArgumentsProvider {
    override def provideArguments(context: ExtensionContext): java.util.stream.Stream[Arguments] = {
      val map = Map("json1" -> simpleRequest1, "json2" -> simpleRequest2)
      val jmap = ImmutableMap
        .builder()
        .put("json1", simpleRequest1)
        .put("json2", simpleRequest2)
        .build()

      java.util.stream.Stream.of(
        Arguments.of(map, toJson(map), new TypeToken[Map[String, SimpleRequest]]() {}),
        Arguments.of(jmap, toJson(jmap), new TypeToken[java.util.Map[String, SimpleRequest]]() {})
      )
    }
  }

  def toJson(messages: Iterable[GeneratedMessage]): String =
    messages.map(printer.print).mkString("[", ",", "]")

  def toJson(messages: java.util.Collection[SimpleRequest]): String = {
    val buffer = new ArrayBuffer[String]()
    messages.forEach { req =>
      buffer += printer.print(req)
    }
    buffer.mkString("[", ",", "]")
  }

  def toJson(messages: Map[String, GeneratedMessage]): String =
    messages
      .map { case (k, v) => s""""$k": ${printer.print(v)}""" }
      .mkString("{", ",", "}")

  def toJson(messages: java.util.Map[String, SimpleRequest]): String = {
    val buffer = new ArrayBuffer[String]()
    messages.forEach { (key, req) =>
      buffer += s""""${key}": ${printer.print(req)}"""
    }
    buffer.mkString("{", ",", "}")
  }
}
