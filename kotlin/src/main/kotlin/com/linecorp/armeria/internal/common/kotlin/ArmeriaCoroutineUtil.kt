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

import com.google.common.base.Preconditions.checkState
import com.google.common.base.Predicate
import com.linecorp.armeria.common.ContextAwareExecutor
import com.linecorp.armeria.common.kotlin.CoroutineContexts
import com.linecorp.armeria.common.kotlin.asCoroutineContext
import com.linecorp.armeria.common.kotlin.asCoroutineDispatcher
import com.linecorp.armeria.internal.common.stream.StreamMessageUtil
import com.linecorp.armeria.server.ServiceRequestContext
import io.netty.util.concurrent.EventExecutor
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.future
import org.reactivestreams.Publisher
import org.reflections.ReflectionUtils
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.kotlinFunction

/**
 * Invokes a suspending function and returns [CompletableFuture].
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun callKotlinSuspendingMethod(
    method: Method,
    target: Any,
    args: Array<Any?>,
    executorService: ExecutorService,
    ctx: ServiceRequestContext,
): CompletableFuture<Any?> {
    val kFunction = checkNotNull(method.kotlinFunction) { "method is not a kotlin function" }
    if (!kFunction.isAccessible) {
        kFunction.isAccessible = true
    }
    val future =
        GlobalScope.future(newCoroutineCtx(executorService, ctx)) {
            val argsMap = toArgMap(kFunction, target, args)
            val response =
                kFunction
                    .callSuspendBy(argsMap)
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
 * Forked from https://github.com/spring-projects/spring-framework/blob/91b9a7537138d7de478c3229069acfe946adcb3a/spring-web/src/main/java/org/springframework/web/method/support/InvocableHandlerMethod.java#L309
 * to support value classes.
 */
private fun toArgMap(
    kFunction: KFunction<*>,
    target: Any,
    args: Array<Any?>,
): Map<KParameter, Any?> {
    val argMap = HashMap<KParameter, Any?>(args.size + 1)
    var index = 0
    for (parameter in kFunction.parameters) {
        when (parameter.kind) {
            KParameter.Kind.INSTANCE -> argMap[parameter] = target
            KParameter.Kind.VALUE -> {
                if (!parameter.isOptional || args[index] != null) {
                    val classifier = parameter.type.classifier
                    if (classifier is KClass<*> && classifier.isValue) {
                        val methods =
                            ReflectionUtils.getAllMethods(
                                classifier.java,
                                Predicate {
                                    it.isSynthetic && Modifier.isStatic(it.modifiers) &&
                                        it.name == "box-impl"
                                },
                            )
                        checkState(
                            methods.size == 1,
                            "Unable to find a single box-impl synthetic static method in %s",
                            classifier.java.name,
                        )
                        // Convert value class to its boxed type.
                        argMap[parameter] = methods.first().invoke(null, args[index])
                    } else {
                        argMap[parameter] = args[index]
                    }
                }
                index++
            }
            KParameter.Kind.EXTENSION_RECEIVER ->
                throw IllegalStateException("Unsupported parameter kind: ${parameter.kind}")
        }
    }
    return argMap
}

/**
 * Converts [Flow] into [Publisher].
 * @see FlowCollectingPublisher
 */
internal fun <T : Any> Flow<T>.asPublisher(
    executor: EventExecutor,
    ctx: ServiceRequestContext,
): Publisher<T> = FlowCollectingPublisher(this, executor, newCoroutineCtx(executor, ctx))

private fun newCoroutineCtx(
    executorService: ExecutorService,
    ctx: ServiceRequestContext,
): CoroutineContext {
    val userContext = CoroutineContexts.get(ctx) ?: EmptyCoroutineContext
    if (executorService is ContextAwareExecutor) {
        return (executorService as ContextAwareExecutor).asCoroutineDispatcher() + userContext
    }
    return executorService.asCoroutineDispatcher() + ctx.asCoroutineContext() + userContext
}
