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

package com.linecorp.armeria.server.protobuf

import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.annotation.{ConsumesJson, Post, ProducesJson}
import com.linecorp.armeria.server.docs.{ContainerTypeSignature, DocService, MapTypeSignature, StructInfo, TypeSignatureType}
import com.linecorp.armeria.server.protobuf.ProtobufDescriptiveTypeInfoProvider._
import com.linecorp.armeria.server.scalapb.{ScalaPbDescriptiveTypeInfoProvider, ServerSuite}
import munit.FunSuite
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson
import testing.scalapb.messages.TestMessage

import scala.concurrent.Future

@GenerateNativeImageTrace
class ScalaPbDescriptiveTypeInfoProviderTest extends FunSuite with ServerSuite {

  override def configureServer: ServerBuilder => Unit = {
    _.annotatedService(new ScalaPbService, Array.emptyObjectArray: _*)
      .serviceUnder("/docs", new DocService)
  }

  test("GeneratedMessage") {
    val provider = new ScalaPbDescriptiveTypeInfoProvider()
    val namedInfo = provider.newDescriptiveTypeInfo(classOf[TestMessage])
    assert(namedInfo.isInstanceOf[StructInfo])
    val structInfo: StructInfo = namedInfo.asInstanceOf[StructInfo]
    assertEquals(structInfo.name, "testing.scalapb.TestMessage")
    assertEquals(structInfo.fields.size(), 19)
    assertEquals(structInfo.fields.get(0).name, "bool")
    assertEquals(structInfo.fields.get(0).typeSignature, BOOL)
    assertEquals(structInfo.fields.get(1).name, "int32")
    assertEquals(structInfo.fields.get(1).typeSignature, INT32)
    assertEquals(structInfo.fields.get(2).name, "int64")
    assertEquals(structInfo.fields.get(2).typeSignature, INT64)
    assertEquals(structInfo.fields.get(3).name, "uint32")
    assertEquals(structInfo.fields.get(3).typeSignature, UINT32)
    assertEquals(structInfo.fields.get(4).name, "uint64")
    assertEquals(structInfo.fields.get(4).typeSignature, UINT64)
    assertEquals(structInfo.fields.get(5).name, "sint32")
    assertEquals(structInfo.fields.get(5).typeSignature, SINT32)
    assertEquals(structInfo.fields.get(6).name, "sint64")
    assertEquals(structInfo.fields.get(6).typeSignature, SINT64)
    assertEquals(structInfo.fields.get(7).name, "fixed32")
    assertEquals(structInfo.fields.get(7).typeSignature, FIXED32)
    assertEquals(structInfo.fields.get(8).name, "fixed64")
    assertEquals(structInfo.fields.get(8).typeSignature, FIXED64)
    assertEquals(structInfo.fields.get(9).name, "float")
    assertEquals(structInfo.fields.get(9).typeSignature, FLOAT)
    assertEquals(structInfo.fields.get(10).name, "double")
    assertEquals(structInfo.fields.get(10).typeSignature, DOUBLE)
    assertEquals(structInfo.fields.get(11).name, "string")
    assertEquals(structInfo.fields.get(11).typeSignature, STRING)
    assertEquals(structInfo.fields.get(12).name, "bytes")
    assertEquals(structInfo.fields.get(12).typeSignature, BYTES)
    assertEquals(structInfo.fields.get(13).name, "test_enum")
    assertEquals(structInfo.fields.get(13).typeSignature.signature, "testing.scalapb.TestEnum")

    val nested = structInfo.fields.get(14)
    assertEquals(nested.name, "nested")
    assertEquals(nested.typeSignature.signature, "testing.scalapb.TestMessage.Nested")

    assertEquals(structInfo.fields.get(15).name, "strings")
    val repeatedTypeSignature = structInfo.fields.get(15).typeSignature
    assertEquals(repeatedTypeSignature.`type`(), TypeSignatureType.ITERABLE)
    val repeatedTypeParameters = repeatedTypeSignature.asInstanceOf[ContainerTypeSignature].typeParameters
    assertEquals(repeatedTypeParameters.size(), 1)
    assertEquals(repeatedTypeParameters.get(0), STRING)
    assertEquals(structInfo.fields.get(16).name, "map")
    val mapTypeSignature = structInfo.fields.get(16).typeSignature
    assertEquals(mapTypeSignature.`type`(), TypeSignatureType.MAP)
    assertEquals(mapTypeSignature.asInstanceOf[MapTypeSignature].keyTypeSignature(), STRING)
    assertEquals(mapTypeSignature.asInstanceOf[MapTypeSignature].valueTypeSignature(), INT32)
    val self = structInfo.fields.get(17)
    assertEquals(self.name, "self")
    assertEquals(self.typeSignature.signature, "testing.scalapb.TestMessage")

    val oneof = structInfo.fields.get(18)
    assertEquals(oneof.name(), "oneof")
    assertEquals(oneof.typeSignature.signature, "testing.scalapb.SimpleOneof")
  }

  test("should not handle com.google.protobuf.Message with ScalaPbDescriptiveTypeInfoProvider") {
    val provider = new ScalaPbDescriptiveTypeInfoProvider()
    assert(provider.newDescriptiveTypeInfo(classOf[com.google.protobuf.Message]) == null)
  }

  test("should not handle scalapb.GenerateMessage with ProtobufNamedTyeInfoProvider") {
    val provider = new ProtobufDescriptiveTypeInfoProvider()
    assert(provider.newDescriptiveTypeInfo(classOf[scalapb.GeneratedMessage]) == null)
    assert(provider.newDescriptiveTypeInfo(classOf[scalapb.GeneratedOneof]) == null)
  }

  test("specification") {
    val client = server.webClient().blocking()
    val response = client
      .prepare()
      .get("/docs/specification.json")
      .asJson(classOf[JsonNode])
      .execute()
      .content()

    val resourceAsStream = classOf[ScalaPbDescriptiveTypeInfoProviderTest].getResourceAsStream(
      "/testing/scalapb/ScalaPbDescriptiveTypeInfoProviderTest_specification.json5")
    val json5Mapper = JsonMapper
      .builder()
      .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature)
      .enable(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature)
      .build()
    val expected = json5Mapper.readTree(resourceAsStream)

    assertEquals(response.get("services").get(0).get("name").textValue, classOf[ScalaPbService].getName)
    assertThatJson(response.get("services").get(0).get("methods"))
      .isEqualTo(expected.get("services").get(0).get("methods"))
    assertThatJson(response.get("structs")).isEqualTo(expected.get("structs"))
  }

  final class ScalaPbService {
    @Post("/json")
    @ConsumesJson
    @ProducesJson def json(req: TestMessage): Future[TestMessage] =
      Future.successful(TestMessage.defaultInstance)
  }
}
