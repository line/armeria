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

package com.linecorp.armeria.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpData
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpObject
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.sse.ServerSentEvent
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Post
import com.linecorp.armeria.server.annotation.ProducesEventStream
import com.linecorp.armeria.server.annotation.ProducesJsonSequences
import com.linecorp.armeria.server.annotation.ProducesOctetStream
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class FlowAnnotatedServiceTest {

    @Test
    fun test_byteStreaming() = runBlocking {
        val req = HttpRequest.streaming(HttpMethod.POST, "/flow/byte-streaming")
        val res = client.execute(req)

        // stream data to server.
        launch {
            req.write(HttpData.ofUtf8("hello"))
            req.whenConsumed().await()
            req.write(HttpData.ofUtf8("world"))
            req.whenConsumed().await()
            req.close()
        }

        res shouldProduce listOf("hello", "world")
    }

    @Test
    fun test_jsonStreaming_string() = runBlocking {
        val req = HttpRequest.streaming(HttpMethod.POST, "/flow/json-string-streaming")
        val res = client.execute(req)

        // stream data to server.
        launch {
            req.write(HttpData.ofUtf8("hello"))
            req.whenConsumed().await()
            req.write(HttpData.ofUtf8("world"))
            req.whenConsumed().await()
            req.close()
        }

        res shouldProduce listOf("\u001E\"hello\"\n", "\u001E\"world\"\n")
    }

    @Test
    fun test_jsonStreaming_obj() = runBlocking {
        client.get("/flow/json-obj-streaming") shouldProduce listOf(
            "\u001E{\"name\":\"foo\",\"age\":10}\n",
            "\u001E{\"name\":\"bar\",\"age\":20}\n",
            "\u001E{\"name\":\"baz\",\"age\":30}\n"
        )
    }

    @Test
    fun test_eventStreaming() = runBlocking {
        client.get("/flow/event-streaming") shouldProduce listOf(
            "id:1\n" + "event:MESSAGE_DELIVERED\n" + "data:{\"message_id\":1}\n" + "\n",
            "id:2\n" + "event:FOLLOW_REQUEST\n" + "data:{\"user_id\":123}\n" + "\n"
        )
    }

    companion object {
        @JvmField
        @RegisterExtension
        val server = object : ServerExtension() {
            override fun configure(sb: ServerBuilder) {
                sb.annotatedService("/flow", object {
                    @Post("/byte-streaming")
                    @ProducesOctetStream
                    fun byteStreaming(req: HttpRequest): Flow<HttpObject> =
                        req.asFlow().filter { it !is HttpHeaders }

                    @Post("/json-string-streaming")
                    @ProducesJsonSequences
                    fun jsonStreamingString(req: HttpRequest): Flow<String> =
                        req.asFlow()
                            .filter { it !is HttpHeaders }
                            .map { (it as HttpData).toStringUtf8() }

                    @Get("/json-obj-streaming")
                    @ProducesJsonSequences
                    fun jsonStreamingObj(): Flow<User> = flow {
                        emit(User(name = "foo", age = 10))
                        emit(User(name = "bar", age = 20))
                        emit(User(name = "baz", age = 30))
                    }

                    @Get("/event-streaming")
                    @ProducesEventStream
                    fun eventStreaming(): Flow<ServerSentEvent> = flow {
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
                })
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

private data class User(
    val name: String,
    val age: Int
)

private suspend infix fun HttpResponse.shouldProduce(stream: List<String>): Unit =
    this.asFlow()
        .filter { it !is HttpHeaders }
        .collectIndexed { i, value ->
            val expected = stream.getOrNull(i) ?: ""
            assertThat((value as HttpData).toStringUtf8()).isEqualTo(expected)
        }
