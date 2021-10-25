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

package com.linecorp.armeria.server.kotlin

import com.google.common.util.concurrent.MoreExecutors
import com.linecorp.armeria.common.util.EventLoopGroups
import com.linecorp.armeria.internal.common.kotlin.FlowCollectingPublisher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import org.reactivestreams.tck.PublisherVerification
import org.reactivestreams.tck.TestEnvironment
import org.testng.annotations.Test
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class FlowCollectingPublisherTest : PublisherVerification<Long>(TestEnvironment(5000L, 2000L)) {
    override fun createPublisher(elements: Long): FlowCollectingPublisher<Long> =
        FlowCollectingPublisher(
            flow {
                (0 until elements).onEach {
                    emit(it)
                }
            },
            EventLoopGroups.directEventLoop()
        )

    override fun createFailedPublisher(): FlowCollectingPublisher<Long> =
        FlowCollectingPublisher(
            flow {
                throw Throwable()
            },
            EventLoopGroups.directEventLoop()
        )

    @Test
    fun test_backPressureOnFlow() {
        val queue: BlockingQueue<Any> = LinkedTransferQueue()
        lateinit var subscription: Subscription

        FlowCollectingPublisher(
            flow {
                (0 until 3).forEach {
                    emit(it)
                    queue.add(it)
                }
            },
            executor = EventLoopGroups.directEventLoop(),
            context = MoreExecutors.directExecutor().asCoroutineDispatcher()
        ).subscribe(object : Subscriber<Int> {
            override fun onSubscribe(s: Subscription) {
                subscription = s
                subscription.request(1L)
            }

            override fun onNext(t: Int) {}

            override fun onError(t: Throwable) {}

            override fun onComplete() {
                queue.add(-1)
            }
        })

        assertThat(queue.poll()).isEqualTo(0)
        assertThat(queue.poll(100L, TimeUnit.MILLISECONDS)).isNull()
        subscription.request(1L)
        assertThat(queue.poll()).isEqualTo(1)
        assertThat(queue.poll(100L, TimeUnit.MILLISECONDS)).isNull()
        subscription.request(1L)
        assertThat(queue.poll()).isEqualTo(2)
        assertThat(queue.poll()).isEqualTo(-1)
    }

    @Test
    fun test_backPressuresWithBuffer() {
        val queue: BlockingQueue<Any> = LinkedTransferQueue()
        lateinit var subscription: Subscription

        FlowCollectingPublisher(
            flow {
                (0 until 5).forEach {
                    emit(it)
                    queue.add(it)
                }
            }.buffer(capacity = 1),
            executor = EventLoopGroups.directEventLoop(),
            context = MoreExecutors.directExecutor().asCoroutineDispatcher()
        ).subscribe(object : Subscriber<Int> {
            override fun onSubscribe(s: Subscription) {
                subscription = s
                subscription.request(1L)
            }

            override fun onNext(t: Int) {}

            override fun onError(t: Throwable) {}

            override fun onComplete() {
                queue.add(-1)
            }
        })

        assertThat(queue.poll()).isEqualTo(0)
        assertThat(queue.poll()).isEqualTo(1)
        assertThat(queue.poll()).isEqualTo(2)
        assertThat(queue.poll(100L, TimeUnit.MILLISECONDS)).isNull()
        subscription.request(1L)
        assertThat(queue.poll()).isEqualTo(3)
        assertThat(queue.poll(100L, TimeUnit.MILLISECONDS)).isNull()
        subscription.request(1L)
        assertThat(queue.poll()).isEqualTo(4)
        subscription.request(3L)
        assertThat(queue.poll()).isEqualTo(-1)
    }

    @Test
    fun test_propagatesCoroutineContext() {
        val context = CoroutineName("custom-context")
        val coroutineNameCaptor = AtomicReference<CoroutineName>()

        FlowCollectingPublisher(
            flow {
                coroutineNameCaptor.set(currentCoroutineContext()[CoroutineName])
                emit(1)
            },
            EventLoopGroups.directEventLoop(),
            context
        ).subscribe(object : Subscriber<Int> {
            override fun onSubscribe(s: Subscription) {}

            override fun onNext(t: Int) {}

            override fun onError(t: Throwable) {}

            override fun onComplete() {}
        })
        await().untilAsserted { assertThat(coroutineNameCaptor.get()).isEqualTo(context) }
    }
}
