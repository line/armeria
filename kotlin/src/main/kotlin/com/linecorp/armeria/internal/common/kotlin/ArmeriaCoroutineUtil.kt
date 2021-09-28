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

@file:JvmName("ArmeriaCoroutineUtil")
@file:Suppress("unused")

package com.linecorp.armeria.internal.common.kotlin

import com.linecorp.armeria.common.kotlin.CoroutineContexts
import com.linecorp.armeria.internal.common.stream.StreamMessageUtil
import com.linecorp.armeria.server.ServiceRequestContext
import io.netty.util.concurrent.EventExecutor
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.future
import org.reactivestreams.Publisher
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.kotlinFunction

/**
 * Invokes a suspending function and returns [CompletableFuture].
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun callKotlinSuspendingMethod(
    method: Method,
    obj: Any,
    args: Array<Any>,
    executorService: ExecutorService,
    ctx: ServiceRequestContext
): CompletableFuture<Any?> {
    val kFunction = checkNotNull(method.kotlinFunction) { "method is not a kotlin function" }
    val future = GlobalScope.future(newCoroutineCtx(executorService, ctx)) {
        val response = kFunction
            .callSuspend(obj, *args)
            .let { if (it == Unit) null else it }

        if (response != null && ctx.isCancelled) {
            // A request has been canceled. Release the response resources to prevent leaks.
            StreamMessageUtil.closeOrAbort(response)
        }
        response
    }

    // Propagate cancellation to upstream.
    ctx.whenRequestCancelled().thenAccept { cause ->
        if (!future.isDone) {
            future.completeExceptionally(cause)
        }
    }

    return future
}

/**
 * Converts [Flow] into [Publisher].
 * @see FlowCollectingPublisher
 */
internal fun <T : Any> Flow<T>.asPublisher(
    executor: EventExecutor,
    ctx: ServiceRequestContext
): Publisher<T> = FlowCollectingPublisher(this, executor, newCoroutineCtx(executor, ctx))

private fun newCoroutineCtx(executorService: ExecutorService, ctx: ServiceRequestContext) =
    // if `coroutineContext` contains a coroutine dispatcher, executorService is not used.
    executorService.asCoroutineDispatcher() + (CoroutineContexts.get(ctx) ?: EmptyCoroutineContext)
