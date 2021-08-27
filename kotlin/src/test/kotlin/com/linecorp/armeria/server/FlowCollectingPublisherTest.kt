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

package com.linecorp.armeria.server

import com.linecorp.armeria.internal.common.kotlin.FlowCollectingPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FlowCollectingPublisherTest {
    @Test
    fun test_shouldCompleteAfterConsumingAllElements() {
        val queue: BlockingQueue<Any> = LinkedTransferQueue()

        FlowCollectingPublisher(flowOf(1, 2))
            .subscribe(object : Subscriber<Int> {
                override fun onSubscribe(s: Subscription) {
                    queue.add("onSubscribe")
                    s.request(Long.MAX_VALUE)
                }

                override fun onNext(t: Int) {
                    queue.add(t)
                }

                override fun onError(t: Throwable) {
                    queue.add("onError") // never reaches here
                }

                override fun onComplete() {
                    queue.add("onComplete")
                }
            })
        assertThat(queue.poll(3, TimeUnit.SECONDS)).isEqualTo("onSubscribe")
        assertThat(queue.poll(3, TimeUnit.SECONDS)).isEqualTo(1)
        assertThat(queue.poll(3, TimeUnit.SECONDS)).isEqualTo(2)
        assertThat(queue.poll(3, TimeUnit.SECONDS)).isEqualTo("onComplete")
    }

    @Test
    fun test_backPressureOnFlow() {
        val queue: BlockingQueue<Any> = LinkedTransferQueue()
        val executor = Executors.newSingleThreadScheduledExecutor()

        FlowCollectingPublisher(
            flow {
                (0 until 3).forEach {
                    emit(it)
                    queue.add(it)
                }
            }
        ).subscribe(object : Subscriber<Int> {
            private lateinit var subscription: Subscription

            override fun onSubscribe(s: Subscription) {
                subscription = s
                subscription.request(1L)
            }

            override fun onNext(t: Int) {
                executor.schedule({ subscription.request(1L) }, 2, TimeUnit.SECONDS)
            }

            override fun onError(t: Throwable) {}

            override fun onComplete() {}
        })

        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(0)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isNull()
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(1)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isNull()
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(2)
    }

    @Test
    fun test_backPressuresWithBuffer() {
        val queue: BlockingQueue<Any> = LinkedTransferQueue()
        val executor = Executors.newSingleThreadScheduledExecutor()

        FlowCollectingPublisher(
            flow {
                (0 until 7).forEach {
                    emit(it)
                    queue.add(it)
                }
            }.buffer(capacity = 1)
        ).subscribe(object : Subscriber<Int> {
            private lateinit var subscription: Subscription

            override fun onSubscribe(s: Subscription) {
                subscription = s
                subscription.request(1L)
            }

            override fun onNext(t: Int) {
                executor.schedule({ subscription.request(1L) }, 2, TimeUnit.SECONDS)
            }

            override fun onError(t: Throwable) {}

            override fun onComplete() {}
        })

        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(0)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(1)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(2)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isNull()
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(3)
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isNull()
        assertThat(queue.poll(1500L, TimeUnit.MILLISECONDS)).isEqualTo(4)
    }

    @Test
    fun test_errorAfterCancellationIsNotReported() {
        val queue: BlockingQueue<Any> = LinkedTransferQueue()

        FlowCollectingPublisher(
            flow {
                try {
                    emit("onNext")
                } catch (t: CancellationException) {
                    queue.add(t)
                } finally {
                    throw RuntimeException()
                }
            }
        ).subscribe(object : Subscriber<String> {
            private lateinit var subscription: Subscription

            override fun onComplete() {
                queue.add("onComplete") // never reaches here
            }

            override fun onSubscribe(s: Subscription) {
                queue.add("onSubscribe")
                subscription = s
                subscription.request(2)
            }

            override fun onNext(t: String) {
                queue.add(t)
                subscription.cancel()
            }

            override fun onError(t: Throwable) {
                queue.add(t) // never reaches here
            }
        })
        assertThat(queue.poll(3, TimeUnit.SECONDS)).isEqualTo("onSubscribe")
        assertThat(queue.poll(3, TimeUnit.SECONDS)).isEqualTo("onNext")
        assertThat(queue.poll(3, TimeUnit.SECONDS)).isInstanceOf(CancellationException::class.java)
        assertThat(queue.poll(3, TimeUnit.SECONDS)).isNull()
    }

    @Test
    fun test_propagatesCoroutineContext() {
        val context = CoroutineName("custom-context")
        val coroutineNameCaptor = AtomicReference<CoroutineName>()

        FlowCollectingPublisher(
            flow = flow {
                coroutineNameCaptor.set(currentCoroutineContext()[CoroutineName])
                emit(1)
            },
            context = context
        ).subscribe(object : Subscriber<Int> {
            override fun onSubscribe(s: Subscription) {}

            override fun onNext(t: Int) {}

            override fun onError(t: Throwable) {}

            override fun onComplete() {}
        })
        await().untilAsserted { assertThat(coroutineNameCaptor.get()).isEqualTo(context) }
    }
}
