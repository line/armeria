/*
 * Copyright 2023 LINE Corporation
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

import com.google.protobuf.ByteString
import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.RequestContext
import com.linecorp.armeria.common.auth.AuthToken
import com.linecorp.armeria.common.grpc.GrpcStatusFunction
import com.linecorp.armeria.grpc.testing.Messages.Payload
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse
import com.linecorp.armeria.grpc.testing.Messages.StreamingInputCallRequest
import com.linecorp.armeria.grpc.testing.Messages.StreamingInputCallResponse
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallRequest
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallResponse
import com.linecorp.armeria.grpc.testing.TestServiceGrpcKt
import com.linecorp.armeria.internal.testing.AnticipatedException
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.auth.Authorizer
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class CoroutineServerInterceptorTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun authorizedUnaryRequest(path: String) {
        runTest {
            val client = GrpcClients.builder(server.httpUri())
                .auth(AuthToken.ofOAuth2(token))
                .pathPrefix(path)
                .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            assertThat(client.unaryCall(SimpleRequest.newBuilder().setFillUsername(true).build()).username)
                .isEqualTo(username)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun unauthorizedUnaryRequest(path: String) {
        runTest {
            val client = GrpcClients.builder(server.httpUri())
                .pathPrefix(path)
                .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            runCatching {
                client.unaryCall(SimpleRequest.newBuilder().setFillUsername(true).build())
            }.onSuccess {
                assert(false) { "Expected exception to be thrown, but none was thrown" }
            }.onFailure { throwable ->
                assert(throwable is StatusException)
                val exception = throwable as StatusException
                assert(exception.status == Status.UNAUTHENTICATED)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun authorizedStreamingOutputCall(path: String) {
        runTest {
            val client = GrpcClients.builder(server.httpUri())
                .auth(AuthToken.ofOAuth2(token))
                .pathPrefix(path)
                .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            client.streamingOutputCall(StreamingOutputCallRequest.getDefaultInstance()).collect {
                assertThat(it.payload.body.toStringUtf8()).isEqualTo(username)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun unauthorizedStreamingOutputCall(path: String) {
        runTest {
            val client = GrpcClients.builder(server.httpUri())
                .pathPrefix(path)
                .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)
            runCatching {
                client.streamingOutputCall(StreamingOutputCallRequest.getDefaultInstance()).collect()
            }.onSuccess {
                assert(false) { "Expected exception to be thrown, but none was thrown" }
            }.onFailure { throwable ->
                assert(throwable is StatusException)
                val exception = throwable as StatusException
                assert(exception.status == Status.UNAUTHENTICATED)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun authorizedStreamingInputCall(path: String) {
        runTest {
            val client = GrpcClients.builder(server.httpUri())
                .auth(AuthToken.ofOAuth2(token))
                .pathPrefix(path)
                .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            assertThat(
                client.streamingInputCall(
                    listOf(StreamingInputCallRequest.getDefaultInstance()).asFlow()
                ).aggregatedPayloadSize
            ).isEqualTo(1)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun unauthorizedStreamingInputCall(path: String) {
        runTest {
            val client = GrpcClients.builder(server.httpUri())
                .pathPrefix(path)
                .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)
            runCatching {
                client.streamingInputCall(listOf(StreamingInputCallRequest.getDefaultInstance()).asFlow())
            }.onSuccess {
                assert(false) { "Expected exception to be thrown, but none was thrown" }
            }.onFailure { throwable ->
                assert(throwable is StatusException)
                val exception = throwable as StatusException
                assert(exception.status == Status.UNAUTHENTICATED)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun authorizedFullDuplexCall(path: String) {
        runTest {
            val client = GrpcClients.builder(server.httpUri())
                .auth(AuthToken.ofOAuth2(token))
                .pathPrefix(path)
                .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            client.fullDuplexCall(listOf(StreamingOutputCallRequest.getDefaultInstance()).asFlow()).collect {
                assertThat(it.payload.body.toStringUtf8()).isEqualTo(username)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun unauthorizedFullDuplexCall(path: String) {
        runTest {
            val client = GrpcClients.builder(server.httpUri())
                .pathPrefix(path)
                .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)
            runCatching {
                client.fullDuplexCall(listOf(StreamingOutputCallRequest.getDefaultInstance()).asFlow())
                    .collect()
            }.onSuccess {
                assert(false) { "Expected exception to be thrown, but none was thrown" }
            }.onFailure { throwable ->
                assert(throwable is StatusException)
                val exception = throwable as StatusException
                assert(exception.status == Status.UNAUTHENTICATED)
            }
        }
    }

    companion object {
        @RegisterExtension
        val server: ServerExtension = object : ServerExtension() {
            override fun configure(sb: ServerBuilder) {
                val statusFunction = GrpcStatusFunction { _: RequestContext, throwable: Throwable, _: Metadata ->
                    if (throwable is AnticipatedException && throwable.message == "Invalid access") {
                        return@GrpcStatusFunction Status.UNAUTHENTICATED
                    }
                    // Fallback to the default.
                    null
                }
                val authInterceptor = AuthInterceptor()
                sb.serviceUnder(
                    "/non-blocking",
                    GrpcService.builder()
                        .exceptionMapping(statusFunction)
                        .intercept(authInterceptor)
                        .addService(TestService())
                        .build()
                )
                sb.serviceUnder(
                    "/blocking",
                    GrpcService.builder()
                        .addService(TestService())
                        .exceptionMapping(statusFunction)
                        .intercept(authInterceptor)
                        .useBlockingTaskExecutor(true)
                        .build()
                )
            }
        }

        private const val username = "Armeria"
        private const val token = "token-1234"

        private class AuthInterceptor : CoroutineServerInterceptor {
            private val authorizer = Authorizer { ctx: ServiceRequestContext, _: Metadata ->
                val future = CompletableFuture<Boolean>()
                ctx.eventLoop().schedule({
                    if (ctx.request().headers().contains("Authorization", "Bearer $token")) {
                        future.complete(true)
                    } else {
                        future.complete(false)
                    }
                }, 100, TimeUnit.MILLISECONDS)
                return@Authorizer future
            }

            override suspend fun <ReqT, RespT> suspendedInterceptCall(
                call: ServerCall<ReqT, RespT>,
                headers: Metadata,
                next: ServerCallHandler<ReqT, RespT>
            ): ServerCall.Listener<ReqT> {
                val result = authorizer.authorize(ServiceRequestContext.current(), headers).await()
                if (result) {
                    return next.startCall(call, headers)
                } else {
                    throw AnticipatedException("Invalid access")
                }
            }
        }

        private class TestService : TestServiceGrpcKt.TestServiceCoroutineImplBase() {
            override suspend fun unaryCall(request: SimpleRequest): SimpleResponse {
                if (request.fillUsername) {
                    return SimpleResponse.newBuilder().setUsername(username).build()
                }

                return SimpleResponse.getDefaultInstance()
            }

            override fun streamingOutputCall(request: StreamingOutputCallRequest): Flow<StreamingOutputCallResponse> {
                return flow {
                    for (i in 1..5) {
                        delay(500)
                        emit(buildReply(username))
                    }
                }
            }

            override suspend fun streamingInputCall(requests: Flow<StreamingInputCallRequest>): StreamingInputCallResponse {
                val names = requests.map { it.payload.body.toString() }.toList()

                return buildReply(names)
            }

            override fun fullDuplexCall(requests: Flow<StreamingOutputCallRequest>): Flow<StreamingOutputCallResponse> {
                return flow {
                    requests.collect {
                        emit(buildReply(username))
                    }
                }
            }
        }

        private fun buildReply(message: String): StreamingOutputCallResponse =
            StreamingOutputCallResponse.newBuilder()
                .setPayload(
                    Payload.newBuilder()
                        .setBody(ByteString.copyFrom(message.toByteArray()))
                )
                .build()

        private fun buildReply(message: List<String>): StreamingInputCallResponse =
            StreamingInputCallResponse.newBuilder()
                .setAggregatedPayloadSize(message.size)
                .build()
    }
}
