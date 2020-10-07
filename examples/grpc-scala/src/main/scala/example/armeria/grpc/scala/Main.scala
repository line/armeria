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

package example.armeria.grpc.scala

import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.docs.{DocService, DocServiceFilter}
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.logging.LoggingService
import example.armeria.grpc.scala.hello.{HelloRequest, HelloServiceGrpc}
import io.grpc.protobuf.services.ProtoReflectionService
import io.grpc.reflection.v1alpha.ServerReflectionGrpc
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

object Main {
  private val logger = LoggerFactory.getLogger(this.getClass)

  def main(args: Array[String]): Unit = {
    val server = newServer(8080, 8443)
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
        server.stop().join()
        logger.info("Server has been stopped.")
    }))
    server.start().join()
    logger.info("Server has been started. Serving DocService at http://127.0.0.1:{}/docs", server.activeLocalPort)
  }

  def newServer(httpPort: Int, httpsPort: Int): Server = {
    val exampleRequest = HelloRequest("Armeria")
    val grpcService =
      GrpcService.builder()
                 .addService(HelloServiceGrpc.bindService(new HelloServiceImpl, ExecutionContext.global))
                 .supportedSerializationFormats(GrpcSerializationFormats.values)
                 .jsonMarshallerFactory(_ => ScalaPBJsonMarshaller())
                 .enableUnframedRequests(true)
                 .build()

    val serviceName = HelloServiceGrpc.SERVICE.getName
    Server.builder()
          .http(httpPort)
          .https(httpsPort)
          .tlsSelfSigned()
          .decorator(LoggingService.newDecorator())
          .service(grpcService)
          .serviceUnder("/docs",
            DocService.builder()
                      .exampleRequests(serviceName, "Hello", exampleRequest)
                      .exampleRequests(serviceName, "LazyHello", exampleRequest)
                      .exampleRequests(serviceName, "BlockingHello", exampleRequest)
                      .exclude(DocServiceFilter.ofServiceName(ServerReflectionGrpc.SERVICE_NAME))
                      .build())
          .build()
  }
}

