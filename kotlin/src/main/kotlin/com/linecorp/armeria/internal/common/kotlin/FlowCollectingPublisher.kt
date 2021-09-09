/*
 * Copyright 2021 LINE Corporation
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
 * under the License
 */

package com.linecorp.armeria.internal.common.kotlin

import com.linecorp.armeria.common.stream.DefaultStreamMessage
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.AbstractCoroutine
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.future.await
import kotlinx.coroutines.handleCoroutineException
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * [Publisher] implementation which emits values collected from [Flow].
 * Reactive streams back-pressure works on its backing [flow] too.
 */
@OptIn(InternalCoroutinesApi::class)
internal class FlowCollectingPublisher<T : Any>(
    private val flow: Flow<T>,
    private val ctx: ServiceRequestContext,
    context: CoroutineContext = EmptyCoroutineContext
) : Publisher<T>, AbstractCoroutine<Unit>(context, initParentJob = false, active = true) {
    private val delegate = DefaultStreamMessage<T>()

    override fun subscribe(s: Subscriber<in T>) {
        this.start(CoroutineStart.DEFAULT, this) {
            flow.collect {
                delegate.write(it)
                delegate.whenConsumed().await()
            }
        }
        delegate.whenComplete().handle { _, _ ->
            if (isActive) {
                cancel()
            }
        }
        delegate.subscribe(s, ctx.eventLoop())
    }

    override fun onCompleted(value: Unit) {
        delegate.close()
    }

    override fun onCancelled(cause: Throwable, handled: Boolean) {
        if (!delegate.tryClose(cause) && !handled) {
            // Last ditch report
            handleCoroutineException(context, cause)
        }
    }
}
