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

package com.linecorp.armeria.common.scalapb

import com.google.common.collect.MapMaker
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller
import com.linecorp.armeria.common.scalapb.ScalaPbJsonMarshaller.{
  jsonDefaultParser,
  jsonDefaultPrinter,
  messageCompanionCache
}
import io.grpc.MethodDescriptor.Marshaller
import java.io.{InputStream, OutputStream}
import java.util.concurrent.ConcurrentMap
import scala.io.{Codec, Source}
import scalapb.json4s.{Parser, Printer}
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

/**
 * A [[com.linecorp.armeria.common.grpc.GrpcJsonMarshaller]] that serializes and deserializes
 * a [[scalapb.GeneratedMessage]] to and from JSON.
 */
final class ScalaPbJsonMarshaller private (
    jsonPrinter: Printer = jsonDefaultPrinter,
    jsonParser: Parser = jsonDefaultParser
) extends GrpcJsonMarshaller {

  override def serializeMessage[A](marshaller: Marshaller[A], message: A, os: OutputStream): Unit = {
    if (!message.isInstanceOf[GeneratedMessage])
      throw new IllegalStateException(
        s"Unexpected message type: ${message.getClass} (expected: ${classOf[GeneratedMessage]})")
    val msg = message.asInstanceOf[GeneratedMessage]
    os.write(jsonPrinter.print(msg).getBytes())
  }

  override def deserializeMessage[A](marshaller: Marshaller[A], in: InputStream): A = {
    val companion = getMessageCompanion(marshaller)
    val jsonString = Source.fromInputStream(in)(Codec.UTF8).mkString
    val message = jsonParser.fromJsonString(jsonString)(companion)
    message.asInstanceOf[A]
  }

  private def getMessageCompanion[A](marshaller: Marshaller[A]): GeneratedMessageCompanion[GeneratedMessage] = {
    val companion = messageCompanionCache.get(marshaller)
    if (companion != null)
      companion
    else
      messageCompanionCache.computeIfAbsent(
        marshaller,
        key => {
          val field = key.getClass.getDeclaredField("companion")
          field.setAccessible(true)
          field.get(marshaller).asInstanceOf[GeneratedMessageCompanion[GeneratedMessage]]
        }
      )
  }
}

/**
 * A companion object for [[com.linecorp.armeria.common.scalapb.ScalaPbJsonMarshaller]].
 */
object ScalaPbJsonMarshaller {

  private val messageCompanionCache: ConcurrentMap[Marshaller[_], GeneratedMessageCompanion[GeneratedMessage]] =
    new MapMaker().weakKeys().makeMap()

  private val jsonDefaultPrinter: Printer = new Printer().includingDefaultValueFields
  private val jsonDefaultParser: Parser = new Parser()

  private val defaultInstance: ScalaPbJsonMarshaller = new ScalaPbJsonMarshaller()

  /**
   * Returns a newly-created [[com.linecorp.armeria.common.scalapb.ScalaPbJsonMarshaller]].
   */
  def apply(
      jsonPrinter: Printer = jsonDefaultPrinter,
      jsonParser: Parser = jsonDefaultParser): ScalaPbJsonMarshaller =
    if (jsonPrinter == jsonDefaultPrinter && jsonParser == jsonDefaultParser)
      defaultInstance
    else
      new ScalaPbJsonMarshaller(jsonPrinter, jsonParser)
}
