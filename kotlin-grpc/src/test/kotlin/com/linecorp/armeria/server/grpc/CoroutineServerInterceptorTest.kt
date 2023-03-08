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

import com.linecorp.armeria.common.RequestContext
import com.linecorp.armeria.common.grpc.GrpcStatusFunction
import com.linecorp.armeria.internal.testing.AnticipatedException
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.auth.Authorizer
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.Status
import kotlinx.coroutines.future.await
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal class CoroutineServerInterceptorTest {
    private class AuthInterceptor : CoroutineServerInterceptor {
        private val authorizer = Authorizer { ctx: ServiceRequestContext, metadata: Metadata ->
            val future = CompletableFuture<Boolean>()
            ctx.eventLoop().schedule({
                if (ctx.request().headers().contains("Authorization", "Bearer token-1234")) {
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

    companion object {
        @RegisterExtension
        val server: ServerExtension = object : ServerExtension() {
            override fun configure(sb: ServerBuilder) {
                val statusFunction = GrpcStatusFunction { ctx: RequestContext?, throwable: Throwable, metadata: Metadata? ->
                    if (throwable is AnticipatedException && throwable.message == "Invalid access") {
                        return@GrpcStatusFunction Status.UNAUTHENTICATED
                    }
                    null
                }
                val authInterceptor = AuthInterceptor()
                sb.serviceUnder(
                    "/non-blocking",
                    GrpcService.builder()
                        .exceptionMapping(statusFunction)
                        .intercept(authInterceptor)
                        .build()
                )
                sb.serviceUnder(
                    "/blocking",
                    GrpcService.builder()
                        .exceptionMapping(statusFunction)
                        .intercept(authInterceptor)
                        .useBlockingTaskExecutor(true)
                        .build()
                )
            }
        }

        object TestServiceImpl {
            fun todo() {
                TODO("If we need same test codes like AsyncServerInterceptorTest, need TestServiceImpl in grpc module's test dir")
            }
        }
    }
}
