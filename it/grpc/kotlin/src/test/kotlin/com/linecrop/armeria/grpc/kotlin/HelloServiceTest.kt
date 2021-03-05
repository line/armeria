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

package com.linecrop.armeria.grpc.kotlin

import com.linecorp.armeria.client.Clients
import com.linecorp.armeria.grpc.kotlin.Hello.HelloRequest
import com.linecorp.armeria.grpc.kotlin.HelloServiceGrpcKt.HelloServiceCoroutineStub
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.grpc.GrpcService
import io.grpc.Status
import io.grpc.Status.Code
import io.grpc.StatusException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class HelloServiceTest {

    @ParameterizedTest
    @MethodSource("uris")
    fun parallelReplyFromServerSideBlockingCall(uri: String) {
        runBlocking {
            val helloService = Clients.newClient(uri, HelloServiceCoroutineStub::class.java)
            repeat(30) {
                launch {
                    val message = helloService.shortBlockingHello(
                        HelloRequest.newBuilder().setName("$it Armeria").build()
                    ).message
                    assertThat(message).isEqualTo("Hello, $it Armeria!")
                }
            }
        }
    }

    @Test
    fun parallelBlockingLotsOfReplies() {
        runBlocking {
            repeat(30) {
                launch {
                    var sequence = 0
                    helloService.blockingLotsOfReplies(HelloRequest.newBuilder().setName("Armeria").build())
                        .collect {
                            assertThat(it.message).isEqualTo("Hello, Armeria! (sequence: ${++sequence})")
                        }
                    assertThat(sequence).isEqualTo(5)
                }
            }
        }
    }

    @Test
    fun parallelShortBlockingLotsOfReplies() {
        runBlocking {
            repeat(30) {
                launch {
                    var sequence = 0
                    helloService.shortBlockingLotsOfReplies(HelloRequest.newBuilder().setName("Armeria").build())
                        .collect {
                            assertThat(it.message).isEqualTo("Hello, Armeria! (sequence: ${++sequence})")
                        }
                    assertThat(sequence).isEqualTo(5)
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("uris")
    fun exceptionMapping(uri: String) {
        val helloService = Clients.newClient(uri, HelloServiceCoroutineStub::class.java)
        assertThatThrownBy {
            runBlocking { helloService.helloError(HelloRequest.newBuilder().setName("Armeria").build()) }
        }.isInstanceOfSatisfying(StatusException::class.java) {
            assertThat(it.status.code).isEqualTo(Code.UNAUTHENTICATED)
            assertThat(it.message).isEqualTo("UNAUTHENTICATED: Armeria is unauthenticated")
        }
    }

    companion object {

        private lateinit var server: Server
        private lateinit var blockingServer: Server
        private lateinit var helloService: HelloServiceCoroutineStub

        @BeforeAll
        @JvmStatic
        fun beforeClass() {
            server = newServer(0)
            server.start().join()

            blockingServer = newServer(0, true)
            blockingServer.start().join()
            helloService = Clients.newClient(protoUri(), HelloServiceCoroutineStub::class.java)
        }

        @AfterAll
        @JvmStatic
        fun afterClass() {
            server.stop().join()
            blockingServer.stop().join()
        }

        @JvmStatic
        fun uris() = listOf(protoUri(), jsonUri(), blockingProtoUri(), blockingJsonUri())
            .map { Arguments.of(it) }

        private fun newServer(httpPort: Int, useBlockingTaskExecutor: Boolean = false): Server {
            return Server.builder()
                .http(httpPort)
                .service(
                    GrpcService.builder()
                        .addService(HelloServiceImpl())
                        .exceptionMapping { throwable, _ ->
                            when (throwable) {
                                is AuthError -> {
                                    Status.UNAUTHENTICATED
                                        .withDescription(throwable.message)
                                        .withCause(throwable)
                                }
                                else -> null
                            }
                        }
                        .useBlockingTaskExecutor(useBlockingTaskExecutor)
                        .build()
                )
                .build()
        }

        private fun protoUri(): String {
            return "gproto+http://127.0.0.1:${server.activeLocalPort()}/"
        }

        private fun jsonUri(): String {
            return "gjson+http://127.0.0.1:${server.activeLocalPort()}/"
        }

        private fun blockingProtoUri(): String {
            return "gproto+http://127.0.0.1:${blockingServer.activeLocalPort()}/"
        }

        private fun blockingJsonUri(): String {
            return "gjson+http://127.0.0.1:${blockingServer.activeLocalPort()}/"
        }
    }
}
