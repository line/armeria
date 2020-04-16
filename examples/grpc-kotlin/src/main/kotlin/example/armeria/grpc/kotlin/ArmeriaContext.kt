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

package example.armeria.grpc.kotlin

import com.linecorp.armeria.common.RequestContext
import com.linecorp.armeria.server.ServiceRequestContext
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * A [CoroutineContext] that propagates an [ServiceRequestContext] to coroutines run using
 * an Armeria context aware executor.
 */
internal object ArmeriaContext : CoroutineContext {
    override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R =
        EmptyCoroutineContext.fold(initial, operation)

    override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
        EmptyCoroutineContext.get(key)

    override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext =
        EmptyCoroutineContext.minusKey(key)

    override fun plus(context: CoroutineContext): CoroutineContext {
        val requestCtx: RequestContext = RequestContext.current()
        return requestCtx.contextAwareExecutor().asCoroutineDispatcher() + context
    }
}

val Dispatchers.Armeria: CoroutineContext
    get() = ArmeriaContext
