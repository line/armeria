/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.sangria

import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node._
import com.fasterxml.jackson.databind.{DeserializationFeature, JsonNode}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.linecorp.armeria.scala.implicits._
import sangria.marshalling._
import scala.util.Try

private object SangriaJackson {
  // Forked from: https://github.com/sangria-graphql/sangria-jackson/blob/main/src/main/scala/sangria/marshalling/jackson.scala

  // TODO(ikhoon): The upstream code is not published to Maven Central yet. Remove this code once
  //               sangria-jackson is officially released.

  private val mapper: JsonMapper = JsonMapper
    .builder()
    .addModule(DefaultScalaModule)
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .build()

  private val json: JsonNodeFactory = mapper.getNodeFactory

  implicit object JacksonResultMarshaller extends ResultMarshaller {

    type Node = JsonNode
    type MapBuilder = ArrayMapBuilder[Node]

    def emptyMapNode(keys: Seq[String]): ArrayMapBuilder[JsonNode] = new ArrayMapBuilder[Node](keys)

    def addMapNodeElem(
        builder: MapBuilder,
        key: String,
        value: Node,
        optional: Boolean
    ): ArrayMapBuilder[JsonNode] = builder.add(key, value)

    def mapNode(builder: MapBuilder): ObjectNode = {
      val node = json.objectNode()
      node.setAll(builder.toMap.asJava)
      node
    }

    def mapNode(keyValues: Seq[(String, JsonNode)]): ObjectNode = {
      val node = json.objectNode()
      node.setAll(keyValues.toMap.asJava)
      node
    }

    def arrayNode(values: Vector[JsonNode]): ArrayNode =
      json.arrayNode().addAll(values.asJava)

    def optionalArrayNodeValue(value: Option[JsonNode]): JsonNode =
      value match {
        case Some(v) => v
        case None    => nullNode
      }

    def scalarNode(
        value: Any,
        typeName: String,
        info: Set[ScalarValueInfo]
    ): ValueNode =
      value match {
        case v: String     => json.textNode(v)
        case v: Boolean    => json.booleanNode(v)
        case v: Int        => json.numberNode(v)
        case v: Long       => json.numberNode(v)
        case v: Float      => json.numberNode(v)
        case v: Double     => json.numberNode(v)
        case v: BigInt     => json.numberNode(v.bigInteger)
        case v: BigDecimal => json.numberNode(v.bigDecimal)
        case v =>
          throw new IllegalArgumentException("Unsupported scalar value: " + v)
      }

    def enumNode(value: String, typeName: String): TextNode =
      json.textNode(value)

    def nullNode: NullNode = json.nullNode()

    def renderCompact(node: JsonNode): String = node.toString

    def renderPretty(node: JsonNode): String = node.toPrettyString
  }

  implicit object JacksonMarshallerForType extends ResultMarshallerForType[JsonNode] {
    val marshaller: JacksonResultMarshaller.type = JacksonResultMarshaller
  }

  implicit object JacksonInputUnmarshaller extends InputUnmarshaller[JsonNode] {

    private def findNodeOpt(node: JsonNode, key: String): Option[JsonNode] = {
      val nodeOrMissing = node.asInstanceOf[ObjectNode].findPath(key)
      if (nodeOrMissing.isMissingNode) {
        None
      } else {
        Some(nodeOrMissing)
      }
    }

    def getRootMapValue(node: JsonNode, key: String): Option[JsonNode] =
      findNodeOpt(node, key)

    def isMapNode(node: JsonNode): Boolean = node.isObject

    def getListValue(node: JsonNode): Seq[JsonNode] = node.asScala.toList

    def isListNode(node: JsonNode): Boolean = node.isArray

    def getMapValue(node: JsonNode, key: String): Option[JsonNode] =
      findNodeOpt(node, key)

    def getMapKeys(node: JsonNode): Seq[String] =
      node.asInstanceOf[ObjectNode].fieldNames.asScala.toList

    def isDefined(node: JsonNode): Boolean =
      node != json.nullNode() && node != json.missingNode()

    def getScalarValue(node: JsonNode): Any =
      node match {
        case b if b.isBoolean => b.asBoolean()
        case i if i.isInt     => i.asInt()
        case d if d.isDouble  => d.asDouble()
        case l if l.isLong    => l.asLong()
        case d if d.isBigDecimal =>
          BigDecimal.javaBigDecimal2bigDecimal(d.decimalValue())
        case t if t.isTextual => t.asText()
        case b if b.isBigInteger =>
          BigInt.javaBigInteger2bigInt(b.bigIntegerValue())
        case f if f.isFloat => f.floatValue()
        case _              => throw new IllegalStateException(s"$node is not a scalar value")
      }

    def getScalaScalarValue(node: JsonNode): Any = getScalarValue(node)

    def isEnumNode(node: JsonNode): Boolean = node.isTextual

    def isScalarNode(node: JsonNode): Boolean =
      node.isBoolean || node.isNumber || node.isTextual

    def isVariableNode(node: JsonNode) = false

    def getVariableName(node: JsonNode) =
      throw new IllegalArgumentException(
        "variables are not supported"
      )

    def render(node: JsonNode): String = node.toString
  }

  private object JacksonToInput extends ToInput[JsonNode, JsonNode] {
    def toInput(value: JsonNode): (JsonNode, JacksonInputUnmarshaller.type) =
      (value, JacksonInputUnmarshaller)
  }

  implicit def JacksonToInput[T <: JsonNode]: ToInput[T, JsonNode] =
    JacksonToInput.asInstanceOf[ToInput[T, JsonNode]]

  private object JacksonFromInput extends FromInput[JsonNode] {
    val marshaller: JacksonResultMarshaller.type = JacksonResultMarshaller

    def fromResult(node: marshaller.Node): JsonNode = node
  }

  implicit def JacksonFromInput[T <: JsonNode]: FromInput[T] =
    JacksonFromInput.asInstanceOf[FromInput[T]]

  implicit object JacksonInputParser extends InputParser[JsonNode] {
    def parse(str: String): Try[JsonNode] = Try(mapper.readTree(str))
  }
}
