package com.linecorp.armeria.server.grpc/*
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

import com.linecorp.armeria.internal.common.kotlin.ArmeriaRequestCoroutineContext
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture

/**
 * A [ServerInterceptor][io.grpc.ServerInterceptor] that is able to asynchronously execute the interceptor without blocking the
 * caller thread.
 * For example:
 * ```kotlin
 * class AuthServerInterceptor : com.linecorp.armeria.server.grpc.CoroutineServerInterceptor {
 *     override suspend fun <ReqT, RespT> suspendedInterceptCall(
 *             call: ServerCall<ReqT, RespT>,
 *             headers: Metadata,
 *             next: ServerCallHandler<ReqT, RespT>
 *     ): ServerCall.Listener<ReqT> = suspendCoroutine {
 *         val future = authorizer.authorize(ServiceRequestContext.current(), headers)
 *         future.whenComplete { result, _ ->
 *             if (result) {
 *                 next.startCall(call, headers)
 *             } else {
 *                 throw AnticipatedException("Invalid access")
 *             }
 *         }
 *     }
 * }
 * ```
 */
interface CoroutineServerInterceptor : AsyncServerInterceptor {

    @OptIn(DelicateCoroutinesApi::class)
    override fun <I : Any, O : Any> asyncInterceptCall(
        call: ServerCall<I, O>,
        headers: Metadata,
        next: ServerCallHandler<I, O>
    ): CompletableFuture<ServerCall.Listener<I>> {
        check(call is AbstractServerCall) { throw IllegalArgumentException("Cannot use ${AsyncServerInterceptor::class.java.name} with a non-Armeria gRPC server") }
        val executor = call.blockingExecutor() ?: call.eventLoop()
        return GlobalScope.future(executor.asCoroutineDispatcher() + ArmeriaRequestCoroutineContext(call.ctx())) {
            suspendedInterceptCall(call, headers, next)
        }
    }

    suspend fun <ReqT, RespT> suspendedInterceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT>
}
