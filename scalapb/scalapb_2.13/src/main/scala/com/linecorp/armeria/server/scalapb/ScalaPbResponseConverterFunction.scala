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

import _root_.scalapb.json4s.Printer
import _root_.scalapb.{GeneratedMessage, GeneratedSealedOneof}
import com.google.common.base.Preconditions.checkArgument
import com.google.common.collect.Iterables
import com.linecorp.armeria.common.annotation.{Nullable, UnstableApi}
import com.linecorp.armeria.common.{HttpData, HttpHeaders, HttpResponse, MediaType, ResponseHeaders}
import com.linecorp.armeria.internal.server.ResponseConversionUtil.aggregateFrom
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.ResponseConverterFunction
import com.linecorp.armeria.server.scalapb.ScalaPbConverterUtil.{defaultJsonPrinter, isProtobuf}
import com.linecorp.armeria.server.scalapb.ScalaPbResponseConverterFunction.{
  fromObjectMH,
  fromPublisherMH,
  fromStreamMH
}
import com.linecorp.armeria.server.streaming.JsonTextSequences
import java.lang.invoke.{MethodHandle, MethodHandles}
import java.lang.reflect.Method
import java.nio.charset.{Charset, StandardCharsets}
import java.util.concurrent.Executor
import java.util.function.{Function => JFunction}
import java.util.stream.Stream
import org.reactivestreams.Publisher
import scala.collection.mutable.ArrayBuffer

/**
 * A [[com.linecorp.armeria.server.annotation.ResponseConverterFunction]] which creates an
 * [[com.linecorp.armeria.common.HttpResponse]] with `content-type: application/protobuf`
 * or `content-type: application/json; charset=utf-8`.
 * If the returned object is an instance of [[scalapb.GeneratedMessage]], the object can be converted to
 * either [[https://developers.google.com/protocol-buffers/docs/encoding Protocol Buffers]] or
 * [[https://developers.google.com/protocol-buffers/docs/proto3#json JSON]] format.
 *
 * ===Conversion of multiple Protobuf messages===
 * A sequence of Protocol Buffer messages can not be handled by this
 * [[com.linecorp.armeria.server.annotation.ResponseConverterFunction]], because Protocol Buffers wire format
 * is not self-delimiting.
 * See [[https://developers.google.com/protocol-buffers/docs/techniques#streaming Streaming Multiple Messages]]
 * for more information.
 * However, [[org.reactivestreams.Publisher]], [[java.util.stream.Stream]], [[scala.Iterable]] and
 * [[java.util.List]] are supported when converting to
 * [[https://datatracker.ietf.org/doc/html/rfc7159#section-5 JSON array]].
 * [[https://datatracker.ietf.org/doc/rfc7464/ JavaScript Object Notation (JSON) Text Sequences]]
 * is also supported for [[org.reactivestreams.Publisher]] and [[java.util.stream.Stream]].
 *
 * Note that this [[com.linecorp.armeria.server.annotation.ResponseConverterFunction]] is applied to
 * the annotated service by default, so you don't have to set explicitly unless you want to
 * use your own [[scalapb.json4s.Printer]].
 *
 * @constructor Creates a new instance with the specified [[scalapb.json4s.Printer]].
 */
@UnstableApi
final class ScalaPbResponseConverterFunction(jsonPrinter: Printer = defaultJsonPrinter)
    extends ResponseConverterFunction {

  override def convertResponse(
      ctx: ServiceRequestContext,
      headers: ResponseHeaders,
      @Nullable result: Any,
      trailers: HttpHeaders): HttpResponse = {
    val contentType = headers.contentType
    val isJsonType = contentType != null && contentType.isJson
    val isJsonSeq = contentType != null && MediaType.JSON_SEQ.is(contentType)

    result match {
      case _ if isJsonSeq =>
        val jfunction: JFunction[Object, String] = obj => toJson(obj)
        result match {
          case publisher: Publisher[_] =>
            fromPublisherMH.invoke(headers, publisher.asInstanceOf[Publisher[Any]], trailers, jfunction)
          case stream: Stream[_] =>
            fromStreamMH.invoke(
              headers,
              stream.asInstanceOf[Stream[Any]],
              trailers,
              ctx.blockingTaskExecutor(),
              jfunction)
          case _ =>
            fromObjectMH.invoke(headers, result, trailers, jfunction);
        }

      case msg: GeneratedSealedOneof =>
        convertMessage(headers, msg.asMessage, trailers, contentType, isJsonType)
      case message: GeneratedMessage =>
        convertMessage(headers, message, trailers, contentType, isJsonType)

      case _ if isJsonType =>
        checkArgument(result != null, "a null value is not allowed for %s", contentType)
        val charset =
          if (contentType == null) StandardCharsets.UTF_8 else contentType.charset(StandardCharsets.UTF_8)
        result match {
          case publisher: Publisher[_] =>
            aggregateFrom(publisher, headers, trailers, toJsonHttpData(_, charset))
          case stream: Stream[_] =>
            aggregateFrom(stream, headers, trailers, toJsonHttpData(_, charset), ctx.blockingTaskExecutor)
          case _ =>
            HttpResponse.of(headers, toJsonHttpData(result, charset), trailers)
        }

      case _ =>
        throw new IllegalArgumentException("Cannot convert a " + result + " to Protocol Buffers wire format")
    }
  }

  private def convertMessage(
      headers: ResponseHeaders,
      result: GeneratedMessage,
      trailers: HttpHeaders,
      @Nullable contentType: MediaType,
      isJson: Boolean): HttpResponse = {
    if (isJson) {
      val charset = contentType.charset(StandardCharsets.UTF_8)
      return HttpResponse.of(headers, toJsonHttpData(result, charset), trailers)
    }

    if (contentType == null)
      HttpResponse.of(
        headers.toBuilder.contentType(MediaType.PROTOBUF).build,
        HttpData.wrap(result.toByteArray),
        trailers)
    else if (isProtobuf(contentType))
      HttpResponse.of(headers, HttpData.wrap(result.toByteArray), trailers)
    else
      ResponseConverterFunction.fallthrough()
  }

  private def toJsonHttpData(message: Any, charset: Charset): HttpData =
    HttpData.of(charset, toJson(message))

  private def toJson(message: Any): String =
    message match {
      case map: java.util.Map[_, _] =>
        val builder = new ArrayBuffer[String](map.size())
        map.forEach((key: Any, value: Any) => builder += s""""$key": ${toJson(value)}""")
        builder.mkString("{", ",", "}")

      case map: Map[_, _] =>
        map.map { case (k, v) => s""""$k": ${toJson(v)}""" }.mkString("{", ",", "}")

      case iterable: java.lang.Iterable[_] =>
        val builder = new ArrayBuffer[String](Iterables.size(iterable))
        iterable.forEach(value => builder += toJson(value))
        builder.mkString("[", ",", "]")

      case iter: Iterable[_] =>
        iter.map(this.toJson).mkString("[", ",", "]")

      case message: GeneratedSealedOneof => jsonPrinter.print(message.asMessage)
      case message: GeneratedMessage     => jsonPrinter.print(message)
      case _ =>
        throw new IllegalStateException(
          s"Unexpected message type : ${message.getClass} " +
            s"(expected: a subtype of ${classOf[GeneratedMessage].getName})")
    }
}

private object ScalaPbResponseConverterFunction {

  private val fromPublisherMH: MethodHandle = {
    val method: Method = classOf[JsonTextSequences].getDeclaredMethod(
      "fromPublisher",
      classOf[ResponseHeaders],
      classOf[Publisher[_]],
      classOf[HttpHeaders],
      classOf[java.util.function.Function[_, _]])
    method.setAccessible(true)

    MethodHandles.lookup.unreflect(method)
  }

  private val fromStreamMH: MethodHandle = {
    val method: Method = classOf[JsonTextSequences].getDeclaredMethod(
      "fromStream",
      classOf[ResponseHeaders],
      classOf[Stream[_]],
      classOf[HttpHeaders],
      classOf[Executor],
      classOf[java.util.function.Function[_, _]])
    method.setAccessible(true)

    MethodHandles.lookup.unreflect(method)
  }

  private val fromObjectMH: MethodHandle = {
    val method: Method = classOf[JsonTextSequences].getDeclaredMethod(
      "fromObject",
      classOf[ResponseHeaders],
      classOf[Object],
      classOf[HttpHeaders],
      classOf[java.util.function.Function[_, _]])
    method.setAccessible(true)

    MethodHandles.lookup.unreflect(method)
  }
}
