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

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.google.common.collect.{ImmutableList, ImmutableMap, ImmutableSet, MapMaker}
import com.google.protobuf.CodedInputStream
import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.annotation.UnstableApi
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.RequestConverterFunction
import com.linecorp.armeria.server.scalapb.ScalaPbConverterUtil.ResultType._
import com.linecorp.armeria.server.scalapb.ScalaPbConverterUtil._
import com.linecorp.armeria.server.scalapb.ScalaPbRequestConverterFunction._
import java.lang.invoke.{MethodHandle, MethodHandles, MethodType}
import java.lang.reflect.{ParameterizedType, Type}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentMap
import javax.annotation.Nullable
import scalapb.json4s.Parser
import scalapb.{GeneratedMessage, GeneratedMessageCompanion, GeneratedSealedOneof}

/**
 * A [[com.linecorp.armeria.server.annotation.RequestConverterFunction]] which converts either
 * a Protocol Buffers or JSON body of the [[com.linecorp.armeria.common.AggregatedHttpRequest]]
 * to an [[scalapb.GeneratedMessage]].
 *
 * The built-in parser of [[scalapb.GeneratedMessage]] for Protocol Buffers is applied only when
 * the `content-type` of [[com.linecorp.armeria.common.RequestHeaders]] is either
 * one of [[com.linecorp.armeria.common.MediaType.PROTOBUF]] or
 * [[com.linecorp.armeria.common.MediaType.OCTET_STREAM]] or
 * `application/x-protobuf`.
 * The [[scalapb.json4s.Parser]] for JSON is applied only when the `content-type` of
 * the [[com.linecorp.armeria.common.RequestHeaders]] is either [[com.linecorp.armeria.common.MediaType.JSON]]
 * or ends with `+json`.
 *
 * ===Conversion of multiple Protobuf messages===
 * A sequence of Protocol Buffer messages can not be handled by this
 * [[com.linecorp.armeria.server.annotation.RequestConverterFunction]],
 * because Protocol Buffers wire format is not self-delimiting.
 * See [[https://developers.google.com/protocol-buffers/docs/techniques#streaming Streaming Multiple Messages]]
 * for more information.
 * However, [[scala.Iterable]] types such as `List[scalapb.GeneratedMessage]` and
 * `Set[scalapb.GeneratedMessage]` are supported only when converted from
 * [[https://datatracker.ietf.org/doc/html/rfc7159#section-5 JSON array]].
 *
 * Note that this [[com.linecorp.armeria.server.annotation.RequestConverterFunction]] is applied to
 * an annotated service by default, so you don't have to specify this converter explicitly unless you want to
 * use your own [[scalapb.json4s.Parser]].
 */
@UnstableApi
object ScalaPbRequestConverterFunction {

  private val methodCache: ConcurrentMap[Class[_], MethodHandle] =
    new MapMaker().weakKeys.makeMap()

  private val toOneofMethodCache: ConcurrentMap[Class[_], MethodHandle] =
    new MapMaker().weakKeys.makeMap()

  private val defaultInstanceCache: ConcurrentMap[Class[_], GeneratedMessage] =
    new MapMaker().weakKeys.makeMap()

  private val companionCache: ConcurrentMap[Class[_], GeneratedMessageCompanion[_]] =
    new MapMaker().weakKeys.makeMap()

  private val defaultJsonParser: Parser = new Parser().ignoringUnknownFields
  private val mapper = new ObjectMapper

  /**
   * Creates a new instance with the specified [[scalapb.json4s.Parser]].
   */
  def apply(jsonParser: Parser = defaultJsonParser): ScalaPbRequestConverterFunction =
    new ScalaPbRequestConverterFunction(jsonParser, ResultType.UNKNOWN)

  private[scalapb] def apply(resultType: ResultType.Value): ScalaPbRequestConverterFunction =
    new ScalaPbRequestConverterFunction(defaultJsonParser, resultType)

  /**
   * Returns a default instance from the specified [[scalapb.GeneratedMessage]]'s class.
   */
  private def getDefaultInstance(clazz: Class[_]): GeneratedMessage = {
    val defaultInstance = defaultInstanceCache.computeIfAbsent(
      clazz,
      key =>
        try {
          val genClass = extractGeneratedMessageType(key)
          val lookup = MethodHandles.publicLookup()
          val mt = MethodType.methodType(genClass)
          val methodHandle = lookup.findStatic(genClass, "defaultInstance", mt)
          methodHandle.invoke()
        } catch {
          case _: NoSuchMethodError | _: IllegalAccessException =>
            unknownGeneratedMessage
        }
    )
    if (defaultInstance == unknownGeneratedMessage)
      throw new IllegalStateException(s"Failed to find a static defaultInstance() method from $clazz")
    defaultInstance
  }

  /**
   * Returns a [[java.lang.invoke.MethodHandle]] for [[scalapb.GeneratedMessageCompanion.merge()]].
   */
  private def getMergeMethod(clazz: Class[_]): MethodHandle = {
    val methodHandle: MethodHandle = methodCache.computeIfAbsent(
      clazz,
      key =>
        try {
          val genClass = extractGeneratedMessageType(key)
          val lookup = MethodHandles.publicLookup()
          val mt = MethodType.methodType(genClass, genClass, classOf[CodedInputStream])
          lookup.findStatic(genClass, "merge", mt)
        } catch {
          case _: NoSuchFieldException | _: ClassNotFoundException =>
            unknownMethodHandle
        }
    )

    if (methodHandle eq unknownMethodHandle)
      throw new IllegalStateException(s"Failed to find a static merge method from $clazz")
    methodHandle
  }

  /**
   * Extracts a [[scalapb.GeneratedMessage]] class from the specified [[scalapb.GeneratedSealedOneof]] or
   * returns itself.
   *
   * @param clazz Either `Class[GeneratedMessage]` or `Class[GeneratedSealedOneof]`.
   */
  private def extractGeneratedMessageType(clazz: Class[_]): Class[GeneratedMessage] = {
    val isOneOf = classOf[GeneratedSealedOneof].isAssignableFrom(clazz)
    val messageType =
      if (isOneOf)
        clazz.getDeclaredMethod("asMessage").getReturnType
      else
        clazz
    messageType.asInstanceOf[Class[GeneratedMessage]]
  }

  /**
   * Converts a [[scalapb.GeneratedMessage]] into a [[scalapb.GeneratedSealedOneof]]
   * if the `expectedResultType` is a subtype of [[scalapb.GeneratedSealedOneof]].
   */
  private def toGenerateMessageOrOneof(
      expectedResultType: Class[_],
      result: GeneratedMessage): Any with Serializable =
    if (classOf[GeneratedSealedOneof].isAssignableFrom(expectedResultType)) {
      val methodHandle = toOneofMethodCache.computeIfAbsent(
        expectedResultType,
        key =>
          try {
            val lookup = MethodHandles.publicLookup()
            val mt = MethodType.methodType(key)
            lookup.findVirtual(result.getClass, s"to${key.getSimpleName}", mt)
          } catch {
            case _: NoSuchFieldException | _: ClassNotFoundException =>
              unknownMethodHandle
          }
      )

      if (methodHandle eq unknownMethodHandle)
        throw new IllegalStateException(
          s"Failed to find to${expectedResultType.getSimpleName} method from $result")

      methodHandle.invoke(result)
    } else
      result

  /**
   * Returns a companion object used to convert JSON to a [[scalapb.GeneratedMessage]].
   */
  private def getCompanion[A <: GeneratedMessage](clazz: Class[_]): GeneratedMessageCompanion[A] = {
    val messageCompanion =
      companionCache
        .computeIfAbsent(
          clazz,
          key => {
            val companionClass = Class.forName(key.getName + "$")
            try companionClass
              .getDeclaredField("MODULE$")
              .get(null)
              .asInstanceOf[GeneratedMessageCompanion[_]]
            catch {
              case _: NoSuchFieldException | _: ClassNotFoundException =>
                unknownGeneratedMessageCompanion
            }
          }
        )
        .asInstanceOf[GeneratedMessageCompanion[A]]

    if (messageCompanion eq unknownGeneratedMessageCompanion)
      throw new IllegalStateException("Failed to find a companion object from " + clazz)

    messageCompanion
  }
}

/**
 * A [[com.linecorp.armeria.server.annotation.RequestConverterFunction]] which converts
 * either a Protocol Buffers or JSON body of the [[com.linecorp.armeria.common.AggregatedHttpRequest]] to
 * an [[scalapb.GeneratedMessage]].
 */
@UnstableApi
final class ScalaPbRequestConverterFunction private (jsonParser: Parser, resultType: ResultType.Value)
    extends RequestConverterFunction {

  @Nullable
  override def convertRequest(
      ctx: ServiceRequestContext,
      request: AggregatedHttpRequest,
      expectedResultType: Class[_],
      @Nullable expectedParameterizedResultType: ParameterizedType): Object = {
    val contentType = request.contentType()
    val charset =
      if (contentType == null) StandardCharsets.UTF_8
      else contentType.charset(StandardCharsets.UTF_8)

    if (resultType == ResultType.PROTOBUF ||
      (resultType == ResultType.UNKNOWN && isProtobufMessage(expectedResultType))) {
      val mergeMH = getMergeMethod(expectedResultType)
      if (contentType == null || isProtobuf(contentType)) {
        val is = request.content.toInputStream
        try {
          val message = mergeMH
            .invoke(getDefaultInstance(expectedResultType), CodedInputStream.newInstance(is))
            .asInstanceOf[GeneratedMessage]
          return toGenerateMessageOrOneof(expectedResultType, message).asInstanceOf[Object]
        } finally if (is != null)
          is.close()
      }
      if (isJson(contentType)) {
        val jsonString = request.content(charset)
        return jsonToScalaPbMessage(expectedResultType, jsonString).asInstanceOf[Object]
      }

      if (!isJson(contentType) || expectedParameterizedResultType == null)
        return RequestConverterFunction.fallthrough
    }

    var resultType0 = resultType
    if (resultType0 == ResultType.UNKNOWN)
      resultType0 = toResultType(expectedParameterizedResultType)
    if (resultType0 == ResultType.UNKNOWN || resultType0 == ResultType.PROTOBUF ||
      contentType == null || isProtobuf(contentType))
      return RequestConverterFunction.fallthrough

    val typeArguments = expectedParameterizedResultType.getActualTypeArguments
    val jsonString = request.content(charset)
    convertToCollection(jsonString, typeArguments, resultType0)
  }

  private def convertToCollection(
      jsonString: String,
      typeArguments: Array[Type],
      resultType: ResultType.Value): AnyRef = {
    val jsonNode = mapper.readTree(jsonString)
    val size = jsonNode.size
    val numTypes = typeArguments.length

    if (jsonNode.isArray && numTypes == 1) {
      val messageType = typeArguments(0).asInstanceOf[Class[_]]
      val iter = jsonNode.iterator()
      resultType match {
        case LIST_PROTOBUF | SET_PROTOBUF =>
          val builder =
            if (resultType == LIST_PROTOBUF) ImmutableList.builderWithExpectedSize[Any with Serializable](size)
            else ImmutableSet.builderWithExpectedSize[Any with Serializable](size)

          while (iter.hasNext)
            builder.add(jsonToScalaPbMessage(messageType, iter.next()))
          builder.build

        case SCALA_LIST_PROTOBUF | SCALA_VECTOR_PROTOBUF | SCALA_SET_PROTOBUF =>
          val builder =
            if (resultType == SCALA_LIST_PROTOBUF)
              List.newBuilder[Any]
            else if (resultType == SCALA_VECTOR_PROTOBUF)
              Vector.newBuilder[Any]
            else
              Set.newBuilder[Any]
          builder.sizeHint(size)

          while (iter.hasNext)
            builder += jsonToScalaPbMessage(messageType, iter.next())
          builder.result()

        case _ => RequestConverterFunction.fallthrough
      }
    } else if (jsonNode.isObject && numTypes == 2) {
      val messageType = typeArguments(1).asInstanceOf[Class[_]]
      val iter = jsonNode.fields
      resultType match {
        case MAP_PROTOBUF =>
          val builder = ImmutableMap.builderWithExpectedSize[String, Any](size)

          while (iter.hasNext) {
            val entry = iter.next
            builder.put(entry.getKey, jsonToScalaPbMessage(messageType, entry.getValue))
          }
          builder.build
        case SCALA_MAP_PROTOBUF =>
          val builder = Map.newBuilder[String, Any]
          builder.sizeHint(size)

          while (iter.hasNext) {
            val entry = iter.next
            builder += entry.getKey -> jsonToScalaPbMessage(messageType, entry.getValue)
          }
          builder.result()

        case _ => RequestConverterFunction.fallthrough
      }
    } else
      RequestConverterFunction.fallthrough
  }

  /**
   * Returns a deserialized [[scalapb.GeneratedMessage]] or [[scalapb.GeneratedSealedOneof]] from the
   * `JsonNode`.
   */
  private def jsonToScalaPbMessage(expectedResultType: Class[_], node: JsonNode): Any with Serializable = {
    val json = mapper.writeValueAsString(node)
    jsonToScalaPbMessage(expectedResultType, json)
  }

  /**
   * Returns a deserialized [[scalapb.GeneratedMessage]] or [[scalapb.GeneratedSealedOneof]] from the `json`.
   */
  private def jsonToScalaPbMessage(expectedResultType: Class[_], json: String): Any with Serializable = {
    val messageType = extractGeneratedMessageType(expectedResultType)
    val message: GeneratedMessage = jsonParser.fromJsonString(json)(getCompanion(messageType))
    toGenerateMessageOrOneof(expectedResultType, message)
  }
}
