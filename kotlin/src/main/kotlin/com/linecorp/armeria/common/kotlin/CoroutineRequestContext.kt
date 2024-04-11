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

package com.linecorp.armeria.common.kotlin

import com.linecorp.armeria.common.RequestContext
import com.linecorp.armeria.common.util.SafeCloseable
import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Converts an instance of [RequestContext] to an implementation of [CoroutineContext] that automatically
 * propagates the [RequestContext]. The propagation is done by [RequestContext.push] when the coroutine is
 * resumed on a thread.
 */
fun RequestContext.asCoroutineContext(): ArmeriaRequestCoroutineContext {
    return ArmeriaRequestCoroutineContext(this)
}

/**
 * Propagates [RequestContext] over coroutines.
 */
class ArmeriaRequestCoroutineContext internal constructor(
    private val requestContext: RequestContext,
) : ThreadContextElement<SafeCloseable>, AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ArmeriaRequestCoroutineContext>

    override fun updateThreadContext(context: CoroutineContext): SafeCloseable {
        return requestContext.push()
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: SafeCloseable,
    ) {
        oldState.close()
    }
}
