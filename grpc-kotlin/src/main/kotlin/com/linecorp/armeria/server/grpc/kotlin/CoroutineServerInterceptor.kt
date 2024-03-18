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

import com.linecorp.armeria.common.annotation.UnstableApi
import com.linecorp.armeria.server.grpc.AsyncServerInterceptor
import io.grpc.Context
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.kotlin.CoroutineContextServerInterceptor
import io.grpc.kotlin.GrpcContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.companionObjectInstance
import kotlin.reflect.full.memberProperties

/**
 * A [ServerInterceptor] that is able to suspend the interceptor without blocking the
 * caller thread.
 * For example:
 * ```kotlin
 * class AuthInterceptor : CoroutineServerInterceptor {
 *     override suspend fun <ReqT, RespT> suspendedInterceptCall(
 *             call: ServerCall<ReqT, RespT>,
 *             headers: Metadata,
 *             next: ServerCallHandler<ReqT, RespT>
 *     ): ServerCall.Listener<ReqT> {
 *         val result = authorizer.authorize(ServiceRequestContext.current(), headers).await()
 *         if (result) {
 *             return next.startCall(call, headers)
 *         } else {
 *             throw AuthenticationException("Invalid access")
 *         }
 *     }
 * }
 * ```
 */
@UnstableApi
interface CoroutineServerInterceptor : AsyncServerInterceptor {
    override fun <I : Any, O : Any> asyncInterceptCall(
        call: ServerCall<I, O>,
        headers: Metadata,
        next: ServerCallHandler<I, O>,
    ): CompletableFuture<ServerCall.Listener<I>> {
        // COROUTINE_CONTEXT_KEY.get():
        //   It is necessary to propagate the CoroutineContext set by the previous CoroutineContextServerInterceptor.
        //   (The ArmeriaRequestCoroutineContext is also propagated by CoroutineContextServerInterceptor)
        // GrpcContextElement.current():
        //   In gRPC-kotlin, the Coroutine Context is propagated using the gRPC Context.
        return CoroutineScope(
            COROUTINE_CONTEXT_KEY.get() + GrpcContextElement.current(),
        ).future {
            suspendedInterceptCall(call, headers, next)
        }
    }

    /**
     * Suspends the current coroutine and intercepts a gRPC server call with the specified call object, headers,
     * and next call handler.
     *
     * @param call the [ServerCall] being intercepted
     * @param headers the [Metadata] of the call
     * @param next the next [ServerCallHandler]
     *
     * @return [ServerCall.Listener] for the intercepted call.
     */
    suspend fun <ReqT, RespT> suspendedInterceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT>

    companion object {
        @Suppress("UNCHECKED_CAST")
        internal val COROUTINE_CONTEXT_KEY: Context.Key<CoroutineContext> =
            CoroutineContextServerInterceptor::class.let { kclass ->
                val companionObject = checkNotNull(kclass.companionObject)
                val property = companionObject.memberProperties.single { it.name == "COROUTINE_CONTEXT_KEY" }
                checkNotNull(
                    property.getter.call(kclass.companionObjectInstance),
                ) as Context.Key<CoroutineContext>
            }
    }
}
