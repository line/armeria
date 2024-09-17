/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.server.grpc.kotlin

import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import testing.grpc.Messages.SimpleRequest
import testing.grpc.Messages.SimpleResponse
import testing.grpc.TestServiceGrpcKt

internal class BlockingExecutorTest {
    @Test
    fun blockingTest() {
        runTest {
            val blockingClient =
                GrpcClients.builder(server.httpUri())
                    .pathPrefix("/blocking")
                    .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            blockingClient.unaryCall(SimpleRequest.getDefaultInstance())

            val nonBlockingClient =
                GrpcClients.builder(server.httpUri())
                    .pathPrefix("/nonblocking")
                    .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            nonBlockingClient.unaryCall(SimpleRequest.getDefaultInstance())
        }
    }

    class ExampleGrpcServiceBlocking :
        TestServiceGrpcKt.TestServiceCoroutineImplBase() {
        @Blocking
        override suspend fun unaryCall(request: SimpleRequest): SimpleResponse {
            val isInEventLoop = ServiceRequestContext.current().eventLoop().inEventLoop()
            assertThat(isInEventLoop).isFalse()
            return SimpleResponse.newBuilder().setUsername(isInEventLoop.toString()).build()
        }
    }

    class ExampleGrpcServiceNonBlocking :
        TestServiceGrpcKt.TestServiceCoroutineImplBase() {
        override suspend fun unaryCall(request: SimpleRequest): SimpleResponse {
            val isInEventLoop = ServiceRequestContext.current().eventLoop().inEventLoop()
            assertThat(isInEventLoop).isTrue()
            return SimpleResponse.newBuilder().setUsername(isInEventLoop.toString()).build()
        }
    }

    companion object {
        @RegisterExtension
        val server: ServerExtension =
            object : ServerExtension() {
                override fun configure(sb: ServerBuilder) {
                    sb.serviceUnder(
                        "/blocking",
                        GrpcService.builder()
                            .addService(ExampleGrpcServiceBlocking())
                            .build(),
                    )
                    sb.serviceUnder(
                        "/nonblocking",
                        GrpcService.builder()
                            .addService(ExampleGrpcServiceNonBlocking())
                            .build(),
                    )
                }
            }
    }
}
