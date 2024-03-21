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

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.google.protobuf.ByteString
import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.RequestContext
import com.linecorp.armeria.common.auth.AuthToken
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction
import com.linecorp.armeria.internal.testing.AnticipatedException
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.auth.Authorizer
import com.linecorp.armeria.server.grpc.AsyncServerInterceptor
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.kotlin.CoroutineContextServerInterceptor
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import testing.grpc.Messages.Payload
import testing.grpc.Messages.SimpleRequest
import testing.grpc.Messages.SimpleResponse
import testing.grpc.Messages.StreamingInputCallRequest
import testing.grpc.Messages.StreamingInputCallResponse
import testing.grpc.Messages.StreamingOutputCallRequest
import testing.grpc.Messages.StreamingOutputCallResponse
import testing.grpc.TestServiceGrpcKt
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@GenerateNativeImageTrace
internal class CoroutineServerInterceptorTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun authorizedUnaryRequest(path: String) {
        runTest {
            val client =
                GrpcClients.builder(server.httpUri())
                    .auth(AuthToken.ofOAuth2(TOKEN))
                    .pathPrefix(path)
                    .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            assertThat(client.unaryCall(SimpleRequest.newBuilder().setFillUsername(true).build()).username)
                .isEqualTo(USER_NAME)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun unauthorizedUnaryRequest(path: String) {
        runTest {
            val client =
                GrpcClients.builder(server.httpUri())
                    .pathPrefix(path)
                    .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            assertThatThrownBy {
                runBlocking { client.unaryCall(SimpleRequest.newBuilder().setFillUsername(true).build()) }
            }.isInstanceOfSatisfying(StatusException::class.java) {
                assertThat(it.status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun authorizedStreamingOutputCall(path: String) {
        runTest {
            val client =
                GrpcClients.builder(server.httpUri())
                    .auth(AuthToken.ofOAuth2(TOKEN))
                    .pathPrefix(path)
                    .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            client.streamingOutputCall(StreamingOutputCallRequest.getDefaultInstance()).collect {
                assertThat(it.payload.body.toStringUtf8()).isEqualTo(USER_NAME)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun unauthorizedStreamingOutputCall(path: String) {
        runTest {
            val client =
                GrpcClients.builder(server.httpUri())
                    .pathPrefix(path)
                    .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            assertThatThrownBy {
                runBlocking {
                    client.streamingOutputCall(StreamingOutputCallRequest.getDefaultInstance()).collect()
                }
            }.isInstanceOfSatisfying(StatusException::class.java) {
                assertThat(it.status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun authorizedStreamingInputCall(path: String) {
        runTest {
            val client =
                GrpcClients.builder(server.httpUri())
                    .auth(AuthToken.ofOAuth2(TOKEN))
                    .pathPrefix(path)
                    .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            assertThat(
                client.streamingInputCall(
                    listOf(StreamingInputCallRequest.getDefaultInstance()).asFlow(),
                ).aggregatedPayloadSize,
            ).isEqualTo(1)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun unauthorizedStreamingInputCall(path: String) {
        runTest {
            val client =
                GrpcClients.builder(server.httpUri())
                    .pathPrefix(path)
                    .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            assertThatThrownBy {
                runBlocking {
                    client.streamingInputCall(listOf(StreamingInputCallRequest.getDefaultInstance()).asFlow())
                }
            }.isInstanceOfSatisfying(StatusException::class.java) {
                assertThat(it.status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun authorizedFullDuplexCall(path: String) {
        runTest {
            val client =
                GrpcClients.builder(server.httpUri())
                    .auth(AuthToken.ofOAuth2(TOKEN))
                    .pathPrefix(path)
                    .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            client.fullDuplexCall(listOf(StreamingOutputCallRequest.getDefaultInstance()).asFlow()).collect {
                assertThat(it.payload.body.toStringUtf8()).isEqualTo(USER_NAME)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun unauthorizedFullDuplexCall(path: String) {
        runTest {
            val client =
                GrpcClients.builder(server.httpUri())
                    .pathPrefix(path)
                    .build(TestServiceGrpcKt.TestServiceCoroutineStub::class.java)

            assertThatThrownBy {
                runBlocking {
                    client.fullDuplexCall(listOf(StreamingOutputCallRequest.getDefaultInstance()).asFlow())
                        .collect()
                }
            }.isInstanceOfSatisfying(StatusException::class.java) {
                assertThat(it.status.code).isEqualTo(Status.Code.UNAUTHENTICATED)
            }
        }
    }

    companion object {
        @RegisterExtension
        val server: ServerExtension =
            object : ServerExtension() {
                override fun configure(sb: ServerBuilder) {
                    val exceptionHandler =
                        GrpcExceptionHandlerFunction { _: RequestContext, throwable: Throwable, _: Metadata ->
                            if (throwable is AnticipatedException && throwable.message == "Invalid access") {
                                return@GrpcExceptionHandlerFunction Status.UNAUTHENTICATED
                            }
                            // Fallback to the default.
                            null
                        }
                    val threadLocalInterceptor = ThreadLocalInterceptor()
                    val authInterceptor = AuthInterceptor()
                    val coroutineNameInterceptor = CoroutineNameInterceptor()
                    sb.serviceUnder(
                        "/non-blocking",
                        GrpcService.builder()
                            .exceptionHandler(exceptionHandler)
                            // applying order is "MyAsyncInterceptor -> coroutineNameInterceptor ->
                            // authInterceptor -> threadLocalInterceptor -> MyAsyncInterceptor"
                            .intercept(
                                MyAsyncInterceptor(),
                                threadLocalInterceptor,
                                authInterceptor,
                                coroutineNameInterceptor,
                                MyAsyncInterceptor(),
                            )
                            .addService(TestService())
                            .build(),
                    )
                    sb.serviceUnder(
                        "/blocking",
                        GrpcService.builder()
                            .addService(TestService())
                            .exceptionHandler(exceptionHandler)
                            // applying order is "MyAsyncInterceptor -> coroutineNameInterceptor ->
                            // authInterceptor -> threadLocalInterceptor -> MyAsyncInterceptor"
                            .intercept(
                                MyAsyncInterceptor(),
                                threadLocalInterceptor,
                                authInterceptor,
                                coroutineNameInterceptor,
                                MyAsyncInterceptor(),
                            )
                            .useBlockingTaskExecutor(true)
                            .build(),
                    )
                }
            }

        private const val USER_NAME = "Armeria"
        private const val TOKEN = "token-1234"

        private val executorDispatcher =
            Executors.newSingleThreadExecutor(
                ThreadFactoryBuilder().setNameFormat("my-executor").build(),
            ).asCoroutineDispatcher()

        private class AuthInterceptor : CoroutineServerInterceptor {
            private val authorizer =
                Authorizer { ctx: ServiceRequestContext, _: Metadata ->
                    val future = CompletableFuture<Boolean>()
                    ctx.eventLoop().schedule({
                        if (ctx.request().headers().contains("Authorization", "Bearer $TOKEN")) {
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
                next: ServerCallHandler<ReqT, RespT>,
            ): ServerCall.Listener<ReqT> {
                assertContextPropagation()

                delay(100)
                assertContextPropagation() // OK even if resume from suspend.

                withContext(executorDispatcher) {
                    // OK even if the dispatcher is switched
                    assertContextPropagation()
                    assertThat(Thread.currentThread().name).contains("my-executor")
                }

                val result = authorizer.authorize(ServiceRequestContext.current(), headers).await()

                if (result) {
                    val ctx = Context.current().withValue(AUTHORIZATION_RESULT_GRPC_CONTEXT_KEY, "OK")
                    return Contexts.interceptCall(ctx, call, headers, next)
                } else {
                    throw AnticipatedException("Invalid access")
                }
            }

            private suspend fun assertContextPropagation() {
                assertThat(ServiceRequestContext.currentOrNull()).isNotNull()
                assertThat(currentCoroutineContext()[CoroutineName]?.name).isEqualTo("my-coroutine-name")
            }

            companion object {
                val AUTHORIZATION_RESULT_GRPC_CONTEXT_KEY: Context.Key<String> =
                    Context.key("authorization-result")
            }
        }

        private class CoroutineNameInterceptor : CoroutineContextServerInterceptor() {
            override fun coroutineContext(
                call: ServerCall<*, *>,
                headers: Metadata,
            ): CoroutineContext {
                return CoroutineName("my-coroutine-name")
            }
        }

        private class ThreadLocalInterceptor : CoroutineContextServerInterceptor() {
            override fun coroutineContext(
                call: ServerCall<*, *>,
                headers: Metadata,
            ): CoroutineContext {
                return THREAD_LOCAL.asContextElement(value = "thread-local-value")
            }

            companion object {
                val THREAD_LOCAL = ThreadLocal<String>()
            }
        }

        private class MyAsyncInterceptor : AsyncServerInterceptor {
            override fun <I : Any, O : Any> asyncInterceptCall(
                call: ServerCall<I, O>,
                headers: Metadata,
                next: ServerCallHandler<I, O>,
            ): CompletableFuture<ServerCall.Listener<I>> {
                val context = Context.current()
                return CompletableFuture.supplyAsync({
                    // NB: When the current thread invoking `startCall` is different from the thread which
                    // started `asyncInterceptCall`, `next.startCall()` should be wrapped with `context.call()`
                    // to propagate the context to the next interceptor.
                    context.call { next.startCall(call, headers) }
                }, EXECUTOR)
            }

            companion object {
                private val EXECUTOR = Executors.newSingleThreadExecutor()
            }
        }

        private class TestService : TestServiceGrpcKt.TestServiceCoroutineImplBase() {
            override suspend fun unaryCall(request: SimpleRequest): SimpleResponse {
                assertContextPropagation()

                delay(100)
                assertContextPropagation() // OK even if resume from suspend.

                withContext(executorDispatcher) {
                    // OK even if the dispatcher is switched
                    assertContextPropagation()
                    assertThat(Thread.currentThread().name).contains("my-executor")
                }

                if (request.fillUsername) {
                    return SimpleResponse.newBuilder().setUsername(USER_NAME).build()
                }
                return SimpleResponse.getDefaultInstance()
            }

            override fun streamingOutputCall(
                request: StreamingOutputCallRequest,
            ): Flow<StreamingOutputCallResponse> {
                return flow {
                    for (i in 1..5) {
                        delay(500)
                        assertContextPropagation()
                        emit(buildReply(USER_NAME))
                    }
                }
            }

            override suspend fun streamingInputCall(
                requests: Flow<StreamingInputCallRequest>,
            ): StreamingInputCallResponse {
                val names = requests.map { it.payload.body.toString() }.toList()

                assertContextPropagation()

                return buildReply(names)
            }

            override fun fullDuplexCall(
                requests: Flow<StreamingOutputCallRequest>,
            ): Flow<StreamingOutputCallResponse> {
                return flow {
                    requests.collect {
                        delay(500)
                        assertContextPropagation()
                        emit(buildReply(USER_NAME))
                    }
                }
            }

            private suspend fun assertContextPropagation() {
                assertThat(ServiceRequestContext.currentOrNull()).isNotNull()
                assertThat(currentCoroutineContext()[CoroutineName]?.name).isEqualTo("my-coroutine-name")
                assertThat(ThreadLocalInterceptor.THREAD_LOCAL.get()).isEqualTo("thread-local-value")
                assertThat(AuthInterceptor.AUTHORIZATION_RESULT_GRPC_CONTEXT_KEY.get()).isEqualTo("OK")
            }
        }

        private fun buildReply(message: String): StreamingOutputCallResponse =
            StreamingOutputCallResponse.newBuilder()
                .setPayload(
                    Payload.newBuilder()
                        .setBody(ByteString.copyFrom(message.toByteArray())),
                )
                .build()

        private fun buildReply(message: List<String>): StreamingInputCallResponse =
            StreamingInputCallResponse.newBuilder()
                .setAggregatedPayloadSize(message.size)
                .build()
    }
}
