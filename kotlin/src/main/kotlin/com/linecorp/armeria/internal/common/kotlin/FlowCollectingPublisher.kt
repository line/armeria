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
import io.netty.util.concurrent.EventExecutor
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * [Publisher] implementation which emits values collected from [Flow].
 * Reactive streams back-pressure works on its backing [flow] too.
 */
internal class FlowCollectingPublisher<T>(
    private val flow: Flow<T>,
    private val executor: EventExecutor,
    private val context: CoroutineContext = EmptyCoroutineContext
) : Publisher<T> {
    @OptIn(DelicateCoroutinesApi::class)
    override fun subscribe(s: Subscriber<in T>) {
        val delegate = DefaultStreamMessage<T>()
        val job = GlobalScope.launch(context) {
            try {
                flow.collect {
                    delegate.write(it)
                    delegate.whenConsumed().await()
                }
            } catch (e: Throwable) {
                if (!delegate.tryClose(e)) {
                    // Delegate to coroutine's exception handling mechanism,
                    // which ignores CancellationException.
                    throw e
                }
                return@launch
            }
            delegate.close()
        }
        delegate.whenComplete().handle { _, _ ->
            if (job.isActive) {
                job.cancel()
            }
        }
        delegate.subscribe(s, executor)
    }
}
