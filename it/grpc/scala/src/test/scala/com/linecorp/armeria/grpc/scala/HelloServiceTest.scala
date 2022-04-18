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

package com.linecorp.armeria.grpc.scala

import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.SerializationFormat
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats
import com.linecorp.armeria.common.scalapb.ScalaPbJsonMarshaller
import com.linecorp.armeria.grpc.scala.HelloServiceTest.{GrpcSerializationProvider, newClient}
import com.linecorp.armeria.grpc.scala.hello.HelloServiceGrpc.HelloServiceStub
import com.linecorp.armeria.grpc.scala.hello.{HelloRequest, HelloServiceGrpc}
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.logging.LoggingService
import io.grpc.Status.Code
import io.grpc.{Status, StatusRuntimeException}
import java.util.stream
import org.assertj.core.api.Assertions.{assertThat, assertThatThrownBy}
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.{Arguments, ArgumentsProvider, ArgumentsSource}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}
import scala.reflect.ClassTag

class HelloServiceTest {

  @ArgumentsSource(classOf[GrpcSerializationProvider])
  @ParameterizedTest
  def exceptionMapping(serializationFormat: SerializationFormat): Unit = {
    val helloService = newClient[HelloServiceStub](serializationFormat)
    assertThatThrownBy(() => Await.result(helloService.helloError(HelloRequest("Armeria")), Duration.Inf))
      .isInstanceOfSatisfying[StatusRuntimeException](
        classOf[StatusRuntimeException],
        e => {
          assertThat(e.getStatus.getCode.value()).isEqualTo(Code.UNAUTHENTICATED.value())
          assertThat(e.getMessage).isEqualTo("UNAUTHENTICATED: Armeria is unauthenticated")
        }
      )
  }
}

object HelloServiceTest {

  var server: Server = _

  private def newClient[A](serializationFormat: SerializationFormat = GrpcSerializationFormats.PROTO)(implicit
      tag: ClassTag[A]): A = {
    GrpcClients
      .builder(uri(serializationFormat))
      .jsonMarshallerFactory(_ => ScalaPbJsonMarshaller())
      .build(tag.runtimeClass)
      .asInstanceOf[A]
  }

  private def uri(serializationFormat: SerializationFormat = GrpcSerializationFormats.PROTO): String =
    s"$serializationFormat+http://127.0.0.1:${server.activeLocalPort()}/"

  @BeforeAll
  def beforeClass(): Unit = {
    server = newServer(0)
    server.start().join()
  }

  def newServer(httpPort: Int): Server = {
    Server
      .builder()
      .http(httpPort)
      .decorator(LoggingService.newDecorator())
      .service(
        GrpcService
          .builder()
          .addService(HelloServiceGrpc.bindService(new HelloServiceImpl, ExecutionContext.global))
          .exceptionMapping {
            case (_, e: AuthError, _) =>
              Status.UNAUTHENTICATED.withDescription(e.getMessage).withCause(e)
            case _ => null
          }
          .jsonMarshallerFactory(_ => ScalaPbJsonMarshaller())
          .build()
      )
      .build()
  }

  private class GrpcSerializationProvider extends ArgumentsProvider {
    override def provideArguments(context: ExtensionContext): stream.Stream[_ <: Arguments] =
      GrpcSerializationFormats
        .values()
        .stream()
        .map(Arguments.of(_))
  }

}
