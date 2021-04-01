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

import com.google.protobuf.{CodedInputStream, CodedOutputStream, Descriptors}
import com.linecorp.armeria.common.MediaType
import java.lang.invoke.{MethodHandle, MethodHandles, MethodType}
import java.lang.reflect.{ParameterizedType, Type}
import org.reactivestreams.Publisher
import scala.concurrent.Future
import scalapb.descriptors.{Descriptor, FieldDescriptor, PValue, Reads}
import scalapb.json4s.Printer
import scalapb.{GeneratedEnumCompanion, GeneratedMessage, GeneratedMessageCompanion, GeneratedSealedOneof}

private[scalapb] object ScalaPbConverterUtil {

  object ResultType extends Enumeration {
    val UNKNOWN, PROTOBUF, LIST_PROTOBUF, SET_PROTOBUF, MAP_PROTOBUF, SCALA_LIST_PROTOBUF,
        SCALA_VECTOR_PROTOBUF, SCALA_SET_PROTOBUF, SCALA_MAP_PROTOBUF = Value
  }

  val X_PROTOBUF: MediaType = MediaType.create("application", "x-protobuf")
  val defaultJsonPrinter: Printer = new Printer().includingDefaultValueFields

  def isJson(contentType: MediaType): Boolean =
    contentType.is(MediaType.JSON) || contentType.subtype.endsWith("+json")

  def isProtobuf(contentType: MediaType): Boolean =
    contentType.is(MediaType.PROTOBUF) || contentType.is(X_PROTOBUF) || contentType.is(MediaType.OCTET_STREAM)

  def toResultType(tpe: Type): ResultType.Value =
    tpe match {
      case clazz: Class[_] if isProtobufMessage(clazz) => ResultType.PROTOBUF
      case parameterizedType: ParameterizedType =>
        val rawType = parameterizedType.getRawType.asInstanceOf[Class[_]]
        val typeArguments = parameterizedType.getActualTypeArguments
        val firstType = typeArguments(0).asInstanceOf[Class[_]]

        val typeArgumentsLength = typeArguments.length
        if (typeArgumentsLength == 1 && isProtobufMessage(firstType))
          if (classOf[List[_]].isAssignableFrom(rawType))
            ResultType.SCALA_LIST_PROTOBUF
          else if (classOf[Vector[_]].isAssignableFrom(rawType))
            ResultType.SCALA_VECTOR_PROTOBUF
          else if (classOf[Set[_]].isAssignableFrom(rawType))
            ResultType.SCALA_SET_PROTOBUF
          else if (classOf[java.util.List[_]].isAssignableFrom(rawType))
            ResultType.LIST_PROTOBUF
          else if (classOf[java.util.Set[_]].isAssignableFrom(rawType))
            ResultType.SET_PROTOBUF
          else
            ResultType.UNKNOWN
        else if (typeArgumentsLength == 2 &&
          isProtobufMessage(typeArguments(1).asInstanceOf[Class[_]])) {
          if (!classOf[String].isAssignableFrom(firstType))
            throw new IllegalStateException(
              s"$firstType cannot be used for the key type of Map. (expected: Map[String, _])")

          if (classOf[Map[_, _]].isAssignableFrom(rawType))
            ResultType.SCALA_MAP_PROTOBUF
          else if (classOf[java.util.Map[_, _]].isAssignableFrom(rawType))
            ResultType.MAP_PROTOBUF
          else
            ResultType.UNKNOWN
        } else
          ResultType.UNKNOWN
      case _ => ResultType.UNKNOWN
    }

  def isSupportedGenericType(tpe: Type): Boolean =
    tpe match {
      case parameterizedType: ParameterizedType =>
        val rawType = parameterizedType.getRawType.asInstanceOf[Class[_]]
        val typeArguments = parameterizedType.getActualTypeArguments
        val firstType = typeArguments(0).asInstanceOf[Class[_]]

        typeArguments.length == 1 &&
        isProtobufMessage(firstType) &&
        (classOf[Future[_]].isAssignableFrom(rawType) ||
        classOf[java.util.concurrent.CompletionStage[_]].isAssignableFrom(rawType) ||
        classOf[Publisher[_]].isAssignableFrom(rawType) ||
        classOf[java.util.stream.Stream[_]].isAssignableFrom(rawType))
      case _ => false
    }

  private[scalapb] def isProtobufMessage(clazz: Class[_]): Boolean =
    classOf[GeneratedMessage].isAssignableFrom(clazz) || classOf[GeneratedSealedOneof].isAssignableFrom(clazz)

  private[scalapb] val unknownGeneratedMessageCompanion: GeneratedMessageCompanion[GeneratedMessage] =
    new GeneratedMessageCompanion[GeneratedMessage] {
      override def merge(a: GeneratedMessage, input: CodedInputStream): GeneratedMessage = ???

      override def javaDescriptor: Descriptors.Descriptor = ???

      override def scalaDescriptor: Descriptor = ???

      override def nestedMessagesCompanions: Seq[GeneratedMessageCompanion[_ <: GeneratedMessage]] = ???

      override def messageReads: Reads[GeneratedMessage] = ???

      override def messageCompanionForFieldNumber(field: Int): GeneratedMessageCompanion[_] = ???

      override def enumCompanionForFieldNumber(field: Int): GeneratedEnumCompanion[_] = ???

      override def defaultInstance: GeneratedMessage = ???
    }

  private[scalapb] val unknownGeneratedMessage: GeneratedMessage = new GeneratedMessage {
    override def writeTo(output: CodedOutputStream): Unit = ???

    override def getFieldByNumber(fieldNumber: Int): Any = ???

    override def getField(field: FieldDescriptor): PValue = ???

    override def companion: GeneratedMessageCompanion[_] = ???

    override def serializedSize: Int = ???

    override def toProtoString: String = ???
  }

  private[scalapb] val unknownMethodHandle: MethodHandle = {
    val publicLookup = MethodHandles.publicLookup()
    val mt = MethodType.methodType(classOf[String], classOf[Any])

    val methodHandle = publicLookup.findStatic(classOf[String], "valueOf", mt)
    assert(methodHandle != null)
    methodHandle
  }
}
