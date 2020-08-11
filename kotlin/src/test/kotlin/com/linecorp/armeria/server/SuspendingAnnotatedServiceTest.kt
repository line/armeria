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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.RequestContext
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.internal.common.RequestContextUtil
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.HttpResult
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.ProducesJson
import com.linecorp.armeria.server.kotlin.CoroutineContextService
import com.linecorp.armeria.server.logging.LoggingService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.slf4j.LoggerFactory
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class SuspendingAnnotatedServiceTest {

    @Test
    fun test_default() {
        get("/default/string").let {
            assertThat(it.status().code()).isEqualTo(200)
            assertThat(it.contentUtf8()).isEqualTo("OK")
        }

        get("/default/responseObject?a=aaa&b=100").let {
            assertThat(it.status().code()).isEqualTo(200)
            assertThat(it.contentUtf8()).isEqualTo("""{"a":"aaa","b":100}""")
        }

        get("/default/httpResponse/hello").let {
            assertThat(it.status().code()).isEqualTo(200)
            assertThat(it.contentUtf8()).isEqualTo("hello")
        }

        get("/default/httpResult/hello").let {
            assertThat(it.status().code()).isEqualTo(200)
            assertThat(it.contentUtf8()).isEqualTo("hello")
        }
    }

    @Test
    fun test_exceptionHandler() {
        val result = get("/default/throwException")
        assertThat(result.status().code()).isEqualTo(500)
        assertThat(result.contentUtf8()).isEqualTo("handled error")
    }

    @Test
    fun test_noContent_whenReturnTypeUnit() {
        val result = delete("/default/noContent")
        assertThat(result.status().code()).isEqualTo(204)
    }

    @Test
    fun test_coroutineContext() {
        val result = get("/kotlinDispatcher/foo")
        assertThat(result.status().code()).isEqualTo(200)
        assertThat(result.contentUtf8()).isEqualTo("OK")
    }

    @Test
    fun test_coroutineContext_dispatchEventLoop() {
        val result = get("/armeriaDispatcher/bar")
        assertThat(result.status().code()).isEqualTo(200)
        assertThat(result.contentUtf8()).isEqualTo("OK")
    }

    @Test
    fun test_blockingAnnotation() {
        val result = get("/blocking/baz")
        assertThat(result.status().code()).isEqualTo(200)
        assertThat(result.contentUtf8()).isEqualTo("OK")
    }

    companion object {
        private val log = LoggerFactory.getLogger(SuspendingAnnotatedServiceTest::class.java)

        @JvmField
        @RegisterExtension
        val server: ServerExtension = object : ServerExtension() {
            override fun configure(serverBuilder: ServerBuilder) {
                serverBuilder
                    .annotatedServiceExtensions(
                        emptyList(),
                        listOf(customJacksonResponseConverterFunction()),
                        listOf(exceptionHandlerFunction())
                    )
                    .annotatedService("/default", object {
                        @Get("/string")
                        suspend fun string(): String {
                            assertInEventLoop()
                            return "OK"
                        }

                        @Get("/responseObject")
                        @ProducesJson
                        suspend fun responseObject(@Param("a") a: String, @Param("b") b: Int): MyResponse {
                            assertInEventLoop()
                            return MyResponse(a = a, b = b)
                        }

                        @Get("/httpResponse/{msg}")
                        suspend fun httpResponse(@Param("msg") msg: String): HttpResponse {
                            assertInEventLoop()
                            return HttpResponse.of(msg)
                        }

                        @Get("/httpResult/{msg}")
                        suspend fun httpResult(@Param("msg") msg: String): HttpResult<String> {
                            assertInEventLoop()
                            return HttpResult.of(
                                ResponseHeaders.of(200),
                                msg
                            )
                        }

                        @Get("/throwException")
                        suspend fun throwException(): HttpResponse {
                            RequestContext.current<ServiceRequestContext>()
                            throw RuntimeException()
                        }

                        @Delete("/noContent")
                        suspend fun noContent() {
                            RequestContext.current<ServiceRequestContext>()
                        }
                    })
                    .annotatedService("/kotlinDispatcher", object {
                        @Get("/foo")
                        suspend fun foo(): String {
                            RequestContext.current<ServiceRequestContext>()
                            withContext(Dispatchers.IO) {
                                RequestContext.current<ServiceRequestContext>()
                                withContext(Dispatchers.Default) {
                                    RequestContext.current<ServiceRequestContext>()
                                }
                            }
                            RequestContext.current<ServiceRequestContext>()
                            return "OK"
                        }
                    })
                    .decoratorUnder("/kotlinDispatcher", CoroutineContextService.newDecorator { ctx ->
                        ArmeriaRequestContext(ctx) + Dispatchers.Default
                    })
                    .annotatedService("/armeriaDispatcher", object {
                        @Get("/bar")
                        suspend fun bar(): String {
                            assertInEventLoop()
                            withContext(Dispatchers.IO) {
                                delay(50)
                            }
                            assertInEventLoop()
                            return "OK"
                        }
                    })
                    .decoratorUnder("/armeriaDispatcher", CoroutineContextService.newDecorator { ctx ->
                        ctx.eventLoop().asCoroutineDispatcher()
                    })
                    .annotatedService("/blocking", object {
                        @Blocking
                        @Get("/baz")
                        suspend fun baz(): String {
                            RequestContext.current<ServiceRequestContext>()
                            assertThat(Thread.currentThread().name).contains("armeria-common-blocking-tasks")
                            return "OK"
                        }
                    })
                    .decorator(LoggingService.newDecorator())
            }
        }

        private fun customJacksonResponseConverterFunction(): JacksonResponseConverterFunction {
            val objectMapper = ObjectMapper()
            objectMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            return JacksonResponseConverterFunction(objectMapper)
        }

        private fun exceptionHandlerFunction() = ExceptionHandlerFunction { _, _, cause ->
            log.info(cause.message, cause)
            HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, "handled error")
        }

        private fun get(path: String): AggregatedHttpResponse {
            val webClient = WebClient.of(server.httpUri())
            return webClient.get(path).aggregate().join()
        }

        private fun delete(path: String): AggregatedHttpResponse {
            val webClient = WebClient.of(server.httpUri())
            return webClient.delete(path).aggregate().join()
        }

        private fun assertInEventLoop() {
            assertThat(
                RequestContext.current<ServiceRequestContext>().eventLoop().inEventLoop()
            ).isTrue()
        }

        private data class MyResponse(val a: String, val b: Int)
    }
}

/**
 * Propagates [ServiceRequestContext] over coroutines.
 */
class ArmeriaRequestContext(
    private val requestContext: ServiceRequestContext?
) : ThreadContextElement<ServiceRequestContext?>, AbstractCoroutineContextElement(Key) {

    companion object Key : CoroutineContext.Key<ArmeriaRequestContext>

    override fun updateThreadContext(context: CoroutineContext): ServiceRequestContext? {
        if (requestContext == null) return null

        return RequestContextUtil.getAndSet(requestContext)
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: ServiceRequestContext?) {
        if (oldState != null) {
            RequestContextUtil.getAndSet<ServiceRequestContext>(oldState)
        }
    }
}
