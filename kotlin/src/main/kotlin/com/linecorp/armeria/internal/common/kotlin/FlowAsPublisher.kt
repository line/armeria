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
 * under the License.
 */

package com.linecorp.armeria.internal.common.kotlin

import kotlinx.coroutines.AbstractCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.handleCoroutineException
import kotlinx.coroutines.intrinsics.startCoroutineCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.util.concurrent.atomic.AtomicLongFieldUpdater
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

fun <T : Any> Flow<T>.asPublisher(context: CoroutineContext = EmptyCoroutineContext): Publisher<T> =
    FlowAsPublisher(this, Dispatchers.Unconfined + context)

/**
 * Adapter that transforms [Flow] into TCK-complaint [Publisher].
 * [cancel] invocation cancels the original flow.
 */
@Suppress("ReactiveStreamsPublisherImplementation")
private class FlowAsPublisher<T : Any>(
    private val flow: Flow<T>,
    private val context: CoroutineContext
) : Publisher<T> {
    override fun subscribe(subscriber: Subscriber<in T>?) {
        if (subscriber == null) throw NullPointerException()
        subscriber.onSubscribe(FlowSubscription(flow, subscriber, context))
    }
}

@OptIn(InternalCoroutinesApi::class)
private class FlowSubscription<T>(
    private val flow: Flow<T>,
    private val subscriber: Subscriber<in T>,
    context: CoroutineContext
) : Subscription, AbstractCoroutine<Unit>(context, initParentJob = false, true) {
    /*
     * We deliberately set initParentJob to false and do not establish parent-child
     * relationship because FlowSubscription doesn't support it
     */
    @Volatile
    private var requested = 0L

    @Volatile
    private var producer: Continuation<Unit>? = createInitialContinuation()

    @Volatile
    private var cancellationRequested = false

    // This code wraps startCoroutineCancellable into continuation
    private fun createInitialContinuation(): Continuation<Unit> = Continuation(coroutineContext) {
        ::flowProcessing.startCoroutineCancellable(this)
    }

    private suspend fun flowProcessing() {
        try {
            consumeFlow()
        } catch (cause: Throwable) {
            @Suppress("INVISIBLE_MEMBER")
            if (!cancellationRequested || isActive || cause !== getCancellationException()) {
                try {
                    subscriber.onError(cause)
                } catch (e: Throwable) {
                    // Last ditch report
                    cause.addSuppressed(e)
                    handleCoroutineException(coroutineContext, cause)
                }
            }
            return
        }
        // We only call this if `consumeFlow()` finished successfully
        try {
            subscriber.onComplete()
        } catch (e: Throwable) {
            handleCoroutineException(coroutineContext, e)
        }
    }

    /*
     * This method has at most one caller at any time (triggered from the `request` method)
     */
    private suspend fun consumeFlow() {
        flow.collect { value ->
            // Emit the value
            subscriber.onNext(value)
            // Suspend if needed before requesting the next value
            if (requestedUpdater.decrementAndGet(this) <= 0) {
                suspendCancellableCoroutine<Unit> {
                    producerUpdater.set(this, it)
                }
            } else {
                // check for cancellation if we don't suspend
                coroutineContext.ensureActive()
            }
        }
    }

    override fun cancel() {
        cancellationRequested = true
        cancel(null)
    }

    override fun request(n: Long) {
        if (n <= 0) return
        val old = requestedUpdater.getAndUpdate(this) { value ->
            val newValue = value + n
            if (newValue <= 0L) Long.MAX_VALUE else newValue
        }
        if (old <= 0L) {
            assert(old == 0L)
            // Emitter is not started yet or has suspended -- spin on race with suspendCancellableCoroutine
            while (true) {
                val producer = producerUpdater.getAndSet(this, null) ?: continue // spin if not set yet
                producer.resume(Unit)
                break
            }
        }
    }

    companion object {
        @JvmStatic
        private val requestedUpdater = AtomicLongFieldUpdater.newUpdater(
            FlowSubscription::class.java, "requested"
        )

        @JvmStatic
        private val producerUpdater = AtomicReferenceFieldUpdater.newUpdater(
            FlowSubscription::class.java, Continuation::class.java, "producer"
        ) as AtomicReferenceFieldUpdater<FlowSubscription<*>, Continuation<Unit>?>
    }
}
