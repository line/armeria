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

package com.linecorp.armeria.server.grpc

import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.RequestContext
import com.linecorp.armeria.common.auth.AuthToken
import com.linecorp.armeria.common.grpc.GrpcStatusFunction
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase
import com.linecorp.armeria.internal.testing.AnticipatedException
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.auth.Authorizer
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import kotlinx.coroutines.future.await
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class CoroutineServerInterceptorTest {

    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun authorizedRequest(path: String) {
        val client = GrpcClients.builder(server.httpUri())
            .pathPrefix(path)
            .auth(AuthToken.ofOAuth2(token))
            .build(TestServiceBlockingStub::class.java)
        val response = client.unaryCall(
            SimpleRequest.newBuilder()
                .setFillUsername(true)
                .build()
        )

        assertThat(response.username).isEqualTo(username)
    }

    @ValueSource(strings = ["/non-blocking", "/blocking"])
    @ParameterizedTest
    fun unauthorizedRequest(path: String) {
        val client = GrpcClients.builder(server.httpUri())
            .pathPrefix(path)
            .build(TestServiceBlockingStub::class.java)

        assertThatThrownBy {
            client.unaryCall(
                SimpleRequest.newBuilder()
                    .setFillUsername(true)
                    .build()
            )
        }.isInstanceOf(StatusRuntimeException::class.java)
            .satisfies({ cause: Throwable ->
                assertThat((cause as StatusRuntimeException).status).isEqualTo(Status.UNAUTHENTICATED)
            })
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

        private class TestService : TestServiceImplBase() {
            override fun unaryCall(request: SimpleRequest, responseObserver: StreamObserver<SimpleResponse>) {
                if (request.fillUsername) {
                    responseObserver.onNext(SimpleResponse.newBuilder().setUsername(username).build())
                } else {
                    responseObserver.onNext(SimpleResponse.getDefaultInstance())
                }
                responseObserver.onCompleted()
            }
        }
    }
}
