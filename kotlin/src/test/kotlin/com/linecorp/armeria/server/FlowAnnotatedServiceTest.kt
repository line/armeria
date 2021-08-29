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

package com.linecorp.armeria.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.sse.ServerSentEvent
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.ProducesEventStream
import com.linecorp.armeria.server.annotation.ProducesJsonSequences
import com.linecorp.armeria.server.annotation.ProducesOctetStream
import com.linecorp.armeria.server.annotation.ProducesText
import com.linecorp.armeria.server.kotlin.CoroutineContextService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

internal class FlowAnnotatedServiceTest {
    @Test
    fun test_byteStreaming() = runBlocking {
        client.get("/flow/byte-streaming") shouldProduce listOf("hello", "world", "")
    }

    @Test
    fun test_jsonStreaming_string() = runBlocking {
        client.get("/flow/json-string-streaming") shouldProduce listOf(
            "\u001E\"hello\"\n", "\u001E\"world\"\n", ""
        )
    }

    @Test
    fun test_jsonStreaming_obj() = runBlocking {
        client.get("/flow/json-obj-streaming") shouldProduce listOf(
            "\u001E{\"name\":\"foo\",\"age\":10}\n",
            "\u001E{\"name\":\"bar\",\"age\":20}\n",
            "\u001E{\"name\":\"baz\",\"age\":30}\n",
            ""
        )
    }

    @Test
    fun test_eventStreaming() = runBlocking {
        client.get("/flow/event-streaming") shouldProduce listOf(
            "id:1\n" + "event:MESSAGE_DELIVERED\n" + "data:{\"message_id\":1}\n" + "\n",
            "id:2\n" + "event:FOLLOW_REQUEST\n" + "data:{\"user_id\":123}\n" + "\n",
            ""
        )
    }

    @Test
    @Disabled
    fun test_blockingAnnotation(): Unit = runBlocking {
        val res = client.get("/flow/blocking-annotation").aggregate().await()
        assertThat(res.status()).isEqualTo(HttpStatus.OK)
        assertThat(res.contentUtf8()).isEqualTo("OK")
    }

    @Test
    fun test_customContext(): Unit = runBlocking {
        val res = client.get("/flow/custom-context").aggregate().await()
        assertThat(res.status()).isEqualTo(HttpStatus.OK)
        assertThat(res.contentUtf8()).isEqualTo("OK")
    }

    @Test
    fun test_customDispatcher(): Unit = runBlocking {
        val res = client.get("/flow/custom-dispatcher").aggregate().await()
        assertThat(res.status()).isEqualTo(HttpStatus.OK)
        assertThat(res.contentUtf8()).isEqualTo("OK")
    }

    @Test
    fun test_cancellation() {
        try {
            client.get("/flow/cancellation").aggregate().get()
        } catch (e: Exception) {
            // do nothing
        }
        Thread.sleep(3000L)
        assertThat(cancelled.get()).isTrue
    }

    companion object {
        @JvmField
        @RegisterExtension
        val server = object : ServerExtension() {
            override fun configure(sb: ServerBuilder) {
                @Suppress("unused")
                sb.apply {
                    annotatedService("/flow", object {
                        @Get("/byte-streaming")
                        @ProducesOctetStream
                        suspend fun byteStreaming(): Flow<ByteArray> = flow {
                            emit("hello".toByteArray())
                            emit("world".toByteArray())
                        }

                        @Get("/json-string-streaming")
                        @ProducesJsonSequences
                        suspend fun jsonStreamingString(): Flow<String> = flow {
                            emit("hello")
                            emit("world")
                        }

                        @Get("/json-obj-streaming")
                        @ProducesJsonSequences
                        suspend fun jsonStreamingObj(): Flow<Member> = flow {
                            emit(Member(name = "foo", age = 10))
                            emit(Member(name = "bar", age = 20))
                            emit(Member(name = "baz", age = 30))
                        }

                        @Get("/event-streaming")
                        @ProducesEventStream
                        suspend fun eventStreaming(): Flow<ServerSentEvent> = flow {
                            emit(
                                ServerSentEvent
                                    .builder()
                                    .id("1")
                                    .event("MESSAGE_DELIVERED")
                                    .data("{\"message_id\":1}")
                                    .build()
                            )
                            emit(
                                ServerSentEvent
                                    .builder()
                                    .id("2")
                                    .event("FOLLOW_REQUEST")
                                    .data("{\"user_id\":123}")
                                    .build()
                            )
                        }

                        @Blocking
                        @Get("/blocking-annotation")
                        @ProducesText
                        suspend fun blockingAnnotation(): Flow<String> = flow {
                            checkNotNull(ServiceRequestContext.currentOrNull())
                            assertThat(Thread.currentThread().name).contains("armeria-common-blocking-tasks")
                            emit("OK")
                        }

                        @Get("/custom-context")
                        @ProducesText
                        suspend fun userContext() = flow {
                            val user = checkNotNull(coroutineContext[User])
                            assertThat(user.name).isEqualTo("Armeria")
                            assertThat(user.role).isEqualTo("Admin")
                            emit("OK")
                        }

                        @Get("/custom-dispatcher")
                        @ProducesText
                        suspend fun dispatcherContext() = flow {
                            assertThat(Thread.currentThread().name).contains("custom-thread")
                            emit("OK")
                        }

                        @Get("/cancellation")
                        @ProducesJsonSequences
                        suspend fun cancellation() = flow {
                            try {
                                emit("OK")
                                delay(2500L)
                                emit("world")
                            } catch (e: CancellationException) {
                                cancelled.set(true)
                            }
                        }
                    })
                    decorator(
                        Route.builder().path("/flow", "/custom-context").build(),
                        CoroutineContextService.newDecorator { User(name = "Armeria", role = "Admin") }
                    )
                    decorator(
                        Route.builder().path("/flow", "/custom-dispatcher").build(),
                        CoroutineContextService.newDecorator {
                            Executors
                                .newSingleThreadExecutor { Thread(it, "custom-thread") }
                                .asCoroutineDispatcher()
                        }
                    )
                    requestTimeoutMillis(2000L)
                }
            }
        }

        lateinit var client: WebClient

        @JvmStatic
        @BeforeAll
        @Suppress("unused")
        fun init() {
            client = WebClient.of(server.httpUri())
        }
    }
}

private val cancelled = AtomicBoolean()

private data class Member(
    val name: String,
    val age: Int
)

private data class User(
    val name: String,
    val role: String
) : AbstractCoroutineContextElement(User) {
    companion object Key : CoroutineContext.Key<User>
}

internal infix fun HttpResponse.shouldProduce(expected: List<String>) {
    val stream = expected.toMutableList()
    val latch = CountDownLatch(1)
    val cause = AtomicReference<Throwable?>()

    this.split().body().subscribe(object : Subscriber<HttpData> {
        lateinit var subscription: Subscription

        override fun onSubscribe(s: Subscription) {
            subscription = s
            s.request(Long.MAX_VALUE)
        }

        override fun onNext(t: HttpData) {
            assertThat(t.toStringUtf8()).isEqualTo(stream.removeAt(0))
        }

        override fun onError(t: Throwable) {
            cause.set(t)
            subscription.cancel()
            latch.countDown()
        }

        override fun onComplete() {
            latch.countDown()
        }
    })
    latch.await()
    cause.get()?.also { throw it }
}
