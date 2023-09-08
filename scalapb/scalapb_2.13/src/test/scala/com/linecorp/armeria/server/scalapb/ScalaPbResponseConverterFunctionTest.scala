/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.scalapb

import com.linecorp.armeria.common.{MediaType, MediaTypeNames}
import com.linecorp.armeria.server.annotation.Produces
import munit.FunSuite
import org.reactivestreams.Publisher
import testing.scalapb.messages.SimpleResponse

import java.lang.reflect.Type
import scala.concurrent.Future

class ScalaPbResponseConverterFunctionTest extends FunSuite {

  test("should tell whether to stream a response") {
    val converter = new ScalaPbResponseConverterFunction
    for (method <- classOf[ProtobufService].getDeclaredMethods) {
      val annotation: Produces = method.getAnnotation(classOf[Produces])
      val produceType: MediaType =
        if (annotation == null) {
          null
        } else {
          MediaType.parse(annotation.value)
        }
      val expected: Option[Boolean] = method.getName match {
        case str if str.endsWith("_nonStreaming") => Some(false)
        case str if str.endsWith("_streaming")    => Some(true)
        case _                                    => None
      }
      val returnType: Type = method.getGenericReturnType
      val isResponseStreaming = converter.isResponseStreaming(returnType, produceType)
      assert(
        Option(isResponseStreaming) == expected,
        s"${method.getName}:${returnType} - isResponseStreaming: ${isResponseStreaming} (expected: $expected)"
      )
    }
  }

  test("shouldn't throw on nested parameterized types") {
    val provider = new ScalaPbResponseConverterFunctionProvider
    val converter = new ScalaPbResponseConverterFunction
    for (method <- classOf[NestedService].getDeclaredMethods) {
      val fn = provider.createResponseConverterFunction(method.getGenericReturnType, converter)
      assert(fn == null)
    }
  }
}

final private class NestedService {
  def nestedList: List[List[SimpleResponse]] = ???
  def nestedMap: Map[String, List[SimpleResponse]] = ???
}

final private class ProtobufService {
  def simple_nonStreaming: SimpleResponse = {
    null
  }

  @Produces(MediaTypeNames.JSON)
  def json_nonStreaming: SimpleResponse = {
    null
  }

  @Produces(MediaTypeNames.PROTOBUF)
  def protobuf_nonStreaming: SimpleResponse = {
    null
  }

  def simpleFuture_nonStreaming: Future[SimpleResponse] = {
    null
  }

  @Produces(MediaTypeNames.JSON)
  def jsonFuture_nonStreaming: Future[SimpleResponse] = {
    null
  }

  @Produces(MediaTypeNames.PROTOBUF)
  def protobufFuture_nonStreaming: Future[SimpleResponse] = {
    null
  }

  @Produces(MediaTypeNames.JSON_SEQ)
  def jsonSeqSimple_nonStreaming: List[SimpleResponse] = {
    null
  }

  @Produces(MediaTypeNames.JSON_SEQ)
  def jsonSeqPublisher_streaming: Publisher[SimpleResponse] = {
    null
  }

  def noContentType_unknown: Publisher[SimpleResponse] = {
    null
  }
}
