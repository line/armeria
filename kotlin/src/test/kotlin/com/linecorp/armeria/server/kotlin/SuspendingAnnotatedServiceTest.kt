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

package com.linecorp.armeria.server.kotlin

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseEntity
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.common.stream.AbortedStreamException
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Blocking
import com.linecorp.armeria.server.annotation.DelegatingResponseConverterFunctionProvider
import com.linecorp.armeria.server.annotation.Delete
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.HttpResult
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.ProducesJson
import com.linecorp.armeria.server.annotation.ResponseConverterFunction
import com.linecorp.armeria.server.logging.LoggingService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.slf4j.LoggerFactory
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

@GenerateNativeImageTrace
@Suppress("RedundantSuspendModifier", "unused")
class SuspendingAnnotatedServiceTest {
    @Test
    fun test_response_compatible() {
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

        get("/default/responseEntity/hello").let {
            assertThat(it.status().code()).isEqualTo(200)
            assertThat(it.contentUtf8()).isEqualTo("hello")
        }
    }

    @Test
    fun test_exceptionHandler() {
        val result = get("/default/throwException")
        assertThat(result.status().code()).isEqualTo(500)
        assertThat(result.contentUtf8()).isEqualTo("RuntimeException")
    }

    @Test
    fun test_noContent_whenReturnTypeUnit() {
        val result = delete("/default/noContent")
        assertThat(result.status().code()).isEqualTo(204)
    }

    @Test
    fun test_coroutineDispatcher_contextAware_byDefault() {
        val result = get("/default/context")
        assertThat(result.status().code()).isEqualTo(200)
        assertThat(result.contentUtf8()).isEqualTo("OK")
    }

    @Test
    fun test_coroutineDispatcher_userSpecified() {
        val result = get("/customContext/foo")
        assertThat(result.status().code()).isEqualTo(200)
        assertThat(result.contentUtf8()).isEqualTo("OK")
    }

    @Test
    fun test_blockingAnnotation() {
        val result = get("/blocking/baz")
        assertThat(result.status().code()).isEqualTo(200)
        assertThat(result.contentUtf8()).isEqualTo("OK")
    }

    @Test
    fun test_downstreamCancellation() {
        get("/downstream-cancellation/long-running-suspend-fun")
        await().untilAsserted { assertThat(cancellationCallCounter).hasValue(1) }

        get("/downstream-cancellation/long-running-suspend-fun-ignore-exception")
        await().untilAsserted { assertThat(cancellationCallCounter).hasValue(2) }
        val serverResponse = httpResponseRef.get()
        assertThat(serverResponse.isComplete).isTrue
        // Make sure that the server response was released.
        assertThatThrownBy { serverResponse.whenComplete().join() }
            .isInstanceOf(CompletionException::class.java)
            .hasCauseInstanceOf(AbortedStreamException::class.java)
    }

    @Test
    fun test_responseConverterFunctionSpi() {
        get("/response-converter-spi/bar").run {
            assertThat(status()).isEqualTo(HttpStatus.OK)
            assertThat(contentUtf8()).isEqualTo("hello, bar!")
        }

        get("/response-converter-spi/bar-in-http-result").run {
            assertThat(status()).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(headers().get("x-custom-header")).isEqualTo("value")
            assertThat(contentUtf8()).isEqualTo("hello, bar!")
        }

        get("/response-converter-spi/bar-in-response-entity").run {
            assertThat(status()).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(headers().get("x-custom-header")).isEqualTo("value")
            assertThat(contentUtf8()).isEqualTo("hello, bar!")
        }
    }

    @Test
    fun test_returnType_nothing() {
        get("/return-nothing-suspend-fun/throw-error").run {
            assertThat(status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
            assertThat(contentUtf8()).isEqualTo("NotImplementedError")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SuspendingAnnotatedServiceTest::class.java)
        private val cancellationCallCounter = AtomicInteger()
        private val httpResponseRef = AtomicReference<HttpResponse>()

        @JvmField
        @RegisterExtension
        val server: ServerExtension =
            object : ServerExtension() {
                override fun configure(serverBuilder: ServerBuilder) {
                    serverBuilder
                        .annotatedServiceExtensions(
                            emptyList(),
                            listOf(customJacksonResponseConverterFunction()),
                            listOf(exceptionHandlerFunction()),
                        )
                        .annotatedService(
                            "/default",
                            object {
                                @Get("/string")
                                suspend fun string(): String {
                                    assertInEventLoop()
                                    return "OK"
                                }

                                @Get("/responseObject")
                                @ProducesJson
                                suspend fun responseObject(
                                    @Param("a") a: String,
                                    @Param("b") b: Int,
                                ): MyResponse {
                                    assertInEventLoop()
                                    return MyResponse(a = a, b = b)
                                }

                                @Get("/httpResponse/{msg}")
                                suspend fun httpResponse(
                                    @Param("msg") msg: String,
                                ): HttpResponse {
                                    assertInEventLoop()
                                    return HttpResponse.of(msg)
                                }

                                @Get("/httpResult/{msg}")
                                suspend fun httpResult(
                                    @Param("msg") msg: String,
                                ): HttpResult<String> {
                                    assertInEventLoop()
                                    return HttpResult.of(
                                        ResponseHeaders.of(200),
                                        msg,
                                    )
                                }

                                @Get("/responseEntity/{msg}")
                                suspend fun responseEntity(
                                    @Param("msg") msg: String,
                                ): ResponseEntity<String> {
                                    assertInEventLoop()
                                    return ResponseEntity.of(
                                        ResponseHeaders.of(200),
                                        msg,
                                    )
                                }

                                @Get("/throwException")
                                suspend fun throwException(): HttpResponse {
                                    ServiceRequestContext.current()
                                    throw RuntimeException()
                                }

                                @Delete("/noContent")
                                suspend fun noContent() {
                                    ServiceRequestContext.current()
                                }

                                @Get("/context")
                                suspend fun bar(): String {
                                    assertInEventLoop()
                                    withContext(Dispatchers.Default) {
                                        delay(1)
                                    }
                                    assertInEventLoop()
                                    return "OK"
                                }
                            },
                        )
                        .annotatedService(
                            "/customContext",
                            object {
                                @Get("/foo")
                                suspend fun foo(): String {
                                    ServiceRequestContext.current()
                                    assertThat(coroutineContext[CoroutineName]?.name).isEqualTo("test")
                                    return "OK"
                                }
                            },
                        )
                        .decoratorUnder(
                            "/customContext",
                            CoroutineContextService.newDecorator {
                                Dispatchers.Default + CoroutineName("test")
                            },
                        )
                        .annotatedService(
                            "/blocking",
                            object {
                                @Blocking
                                @Get("/baz")
                                suspend fun baz(): String {
                                    ServiceRequestContext.current()
                                    assertThat(
                                        Thread.currentThread().name,
                                    ).contains("armeria-common-blocking-tasks")
                                    return "OK"
                                }
                            },
                        )
                        .annotatedService(
                            "/downstream-cancellation",
                            object {
                                @Get("/long-running-suspend-fun")
                                suspend fun longRunningSuspendFun(): String {
                                    try {
                                        delay(10000L)
                                    } catch (e: CancellationException) {
                                        cancellationCallCounter.incrementAndGet()
                                        throw e
                                    }
                                    return "OK"
                                }

                                @Get("/long-running-suspend-fun-ignore-exception")
                                suspend fun unsafeLongRunningSuspendFun(): HttpResponse {
                                    try {
                                        delay(10000L)
                                    } catch (ignored: CancellationException) {
                                        cancellationCallCounter.incrementAndGet()
                                    }
                                    val response = HttpResponse.of("OK")
                                    httpResponseRef.set(response)
                                    return response
                                }
                            },
                        )
                        .annotatedService(
                            "/response-converter-spi",
                            object {
                                @Get("/bar")
                                suspend fun bar() = Bar()

                                @Get("/bar-in-http-result")
                                suspend fun barInHttpResult() =
                                    HttpResult.of(
                                        ResponseHeaders.of(HttpStatus.BAD_REQUEST, "x-custom-header", "value"),
                                        Bar(),
                                    )

                                @Get("/bar-in-response-entity")
                                suspend fun barInReponseENtity() =
                                    ResponseEntity.of(
                                        ResponseHeaders.of(HttpStatus.BAD_REQUEST, "x-custom-header", "value"),
                                        Bar(),
                                    )
                            },
                        )
                        .annotatedService(
                            "/return-nothing-suspend-fun",
                            object {
                                @Get("/throw-error")
                                suspend fun returnNothingSuspendFun(): Nothing {
                                    throw NotImplementedError()
                                }
                            },
                        )
                        .decorator(LoggingService.newDecorator())
                        .requestTimeoutMillis(500L) // to test cancellation
                }
            }

        private fun customJacksonResponseConverterFunction(): JacksonResponseConverterFunction {
            val objectMapper = ObjectMapper()
            objectMapper.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            return JacksonResponseConverterFunction(objectMapper)
        }

        private fun exceptionHandlerFunction() =
            ExceptionHandlerFunction { _, _, cause ->
                log.info(cause.message, cause)
                HttpResponse.of(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.PLAIN_TEXT_UTF_8,
                    cause.javaClass.simpleName,
                )
            }

        private fun get(path: String): AggregatedHttpResponse {
            val webClient = WebClient.of(server.httpUri()).blocking()
            return webClient.get(path)
        }

        private fun delete(path: String): AggregatedHttpResponse {
            val webClient = WebClient.of(server.httpUri()).blocking()
            return webClient.delete(path)
        }

        private fun assertInEventLoop() {
            assertThat(
                ServiceRequestContext.current().eventLoop().inEventLoop(),
            ).isTrue()
        }

        private data class MyResponse(val a: String, val b: Int)
    }

    internal class BarResponseConverterFunctionProvider : DelegatingResponseConverterFunctionProvider {
        override fun createResponseConverterFunction(
            returnType: Type,
            responseConverter: ResponseConverterFunction,
        ): ResponseConverterFunction? =
            returnType.toClass()?.let {
                when {
                    Bar::class.java.isAssignableFrom(it) -> BarResponseConverterFunction(responseConverter)
                    else -> null
                }
            }

        private fun Type.toClass(): Class<*>? =
            when (this) {
                is ParameterizedType -> this.rawType as Class<*>
                is Class<*> -> this
                else -> null
            }
    }

    private class BarResponseConverterFunction(
        private val responseConverter: ResponseConverterFunction,
    ) : ResponseConverterFunction {
        override fun convertResponse(
            ctx: ServiceRequestContext,
            headers: ResponseHeaders,
            result: Any?,
            trailers: HttpHeaders,
        ): HttpResponse =
            when (result) {
                is Bar -> responseConverter.convertResponse(ctx, headers, "hello, bar!", trailers)
                else -> ResponseConverterFunction.fallthrough()
            }
    }

    private class Bar
}
