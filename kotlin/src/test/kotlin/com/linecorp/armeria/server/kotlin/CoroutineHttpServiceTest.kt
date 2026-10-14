/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.kotlin

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.server.Route
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CoroutineHttpServiceTest {
    companion object {
        @JvmField
        @RegisterExtension
        val server =
            object : ServerExtension() {
                override fun configure(sb: ServerBuilder) {
                    sb
                        .service(
                            "/hello",
                            CoroutineHttpService { ctx, req ->
                                assertContextPropagation()
                                HttpResponse.of("hello world")
                            },
                        ).decorator(
                            CoroutineContextService.newDecorator { ctx ->
                                CoroutineName("my-coroutine-name")
                            },
                        )

                    val decorator = CoroutineContextService.newDecorator { CoroutineName("my-coroutine-name") }
                    sb
                        .coroutineService("/ext") { _, _ ->
                            assertContextPropagation()
                            delay(1)
                            HttpResponse.of("ext")
                        }.coroutineService(Route.builder().path("/ext-route").build()) { _, _ ->
                            assertContextPropagation()
                            HttpResponse.of("ext-route")
                        }.coroutineServiceUnder("/ext-prefix") { ctx, _ ->
                            assertContextPropagation()
                            HttpResponse.of(ctx.mappedPath())
                        }.decorator(decorator)

                    sb
                        .virtualHost("foo.com")
                        .coroutineService("/vhost") { _, _ ->
                            assertContextPropagation()
                            HttpResponse.of("vhost")
                        }.decorator(decorator)
                }
            }

        private suspend fun assertContextPropagation() {
            assertThat(ServiceRequestContext.currentOrNull()).isNotNull()
            assertThat(currentCoroutineContext()[CoroutineName]?.name).isEqualTo("my-coroutine-name")
        }
    }

    @Test
    fun `Should return hello world when call hello coroutine service`() =
        runTest {
            val response = server.blockingWebClient().get("/hello")
            assertThat(response.status()).isEqualTo(HttpStatus.OK)
            assertThat(response.contentUtf8()).isEqualTo("hello world")
        }

    @ParameterizedTest
    @CsvSource(
        "/ext, ext",
        "/ext-route, ext-route",
        "/ext-prefix/foo, /foo",
    )
    fun `Should serve services bound via coroutineService extensions`(
        path: String,
        expected: String,
    ) {
        val response = server.blockingWebClient().get(path)
        assertThat(response.status()).isEqualTo(HttpStatus.OK)
        assertThat(response.contentUtf8()).isEqualTo(expected)
    }

    @Test
    fun `Should serve service bound via VirtualHostBuilder coroutineService`() {
        val response =
            server
                .blockingWebClient { it.addHeader(HttpHeaderNames.AUTHORITY, "foo.com:${server.httpPort()}") }
                .get("/vhost")
        assertThat(response.status()).isEqualTo(HttpStatus.OK)
        assertThat(response.contentUtf8()).isEqualTo("vhost")
    }
}
