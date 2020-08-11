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

@file:JvmName("CoroutineUtil")

package com.linecorp.armeria.internal.common.kotlin

import com.linecorp.armeria.common.RequestContext
import com.linecorp.armeria.common.kotlin.CoroutineContextUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.kotlinFunction

/**
 * Invokes a suspending function and returns [CompletableFuture].
 */
fun callKotlinSuspendingMethod(
    method: Method,
    obj: Any,
    args: Array<Any>,
    ctx: RequestContext
): CompletableFuture<Any?> {
    val kFunction = checkNotNull(method.kotlinFunction) { "method is not suspending function" }
    val coroutineContext = CoroutineContextUtil.getCoroutineContext(ctx)
    val newContext = Dispatchers.Unconfined + (coroutineContext ?: EmptyCoroutineContext)
    return GlobalScope.future(newContext) {
        kFunction
            .callSuspend(obj, *args)
            .let { if (it == Unit) null else it }
    }
}
