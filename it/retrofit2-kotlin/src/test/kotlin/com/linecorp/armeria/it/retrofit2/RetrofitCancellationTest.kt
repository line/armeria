/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.it.retrofit2

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.ClientRequestContext
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.limit.ConcurrencyLimit
import com.linecorp.armeria.client.limit.ConcurrencyLimitingClient
import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofit
import com.linecorp.armeria.client.retry.RetryRule
import com.linecorp.armeria.client.retry.RetryingClient
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.Server
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Url
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Timeout(15)
class RetrofitCancellationTest {
    enum class Route(
        val retry: Boolean,
        val redirect: Boolean,
    ) {
        DIRECT(false, false),
        RETRY(true, false),
        REDIRECT(false, true),
        RETRY_REDIRECT(true, true),
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun cancellingSuspendBodyAbortsHttp(route: Route) {
        runBlocking {
            Exchange(route).use { exchange ->
                val job =
                    launch(start = CoroutineStart.UNDISPATCHED) { exchange.api.body(exchange.path).close() }
                withTimeout(5000) { exchange.arrived.await() }
                job.cancelAndJoin()
                assertThat(job.isCancelled).isTrue()
                exchange.assertAborted()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun cancellingSuspendResponseAbortsHttp(route: Route) {
        runBlocking {
            Exchange(route).use { exchange ->
                val job =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        exchange.api
                            .response(exchange.path)
                            .body()
                            ?.close()
                    }
                withTimeout(5000) { exchange.arrived.await() }
                job.cancelAndJoin()
                assertThat(job.isCancelled).isTrue()
                exchange.assertAborted()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun suspendCallsCompleteNormally(route: Route) {
        runBlocking {
            Exchange(route).use { exchange ->
                val result =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        exchange.api.body(exchange.path).use { it.string() }
                    }
                withTimeout(5000) { exchange.arrived.await() }
                exchange.complete()
                assertThat(withTimeout(5000) { result.await() }).isEqualTo("ok")
                assertThat(exchange.transport.get(5, TimeUnit.SECONDS)).isNull()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Route::class)
    fun cancellingRetrofitFutureAbortsHttp(route: Route) {
        runBlocking {
            Exchange(route).use { exchange ->
                val future = exchange.api.future(exchange.path)
                withTimeout(5000) { exchange.arrived.await() }
                assertThat(future).isNotDone()
                future.cancel(true)
                exchange.assertAborted()
                assertThat(future).isCompletedExceptionally()
            }
        }
    }

    @Test
    fun cancellingFutureWithoutInterruptionLeavesHttpRunning() {
        runBlocking {
            Exchange(Route.DIRECT).use { exchange ->
                val future = exchange.api.future(exchange.path)
                withTimeout(5000) { exchange.arrived.await() }
                assertThat(future.cancel(false)).isTrue()
                assertThat(exchange.transport).isNotDone()
                exchange.complete()
                assertThat(exchange.transport.get(5, TimeUnit.SECONDS)).isNull()
            }
        }
    }

    @Test
    fun cancellingQueuedCoroutineReleasesItsWaitingSlot() {
        runBlocking {
            val limit =
                ConcurrencyLimit
                    .builder(1)
                    .maxPendingAcquisitions(1)
                    .timeoutMillis(0)
                    .build()
            val context = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"))
            limit.acquire(context).get(5, TimeUnit.SECONDS).use { held ->
                val queued = CompletableDeferred<Unit>()
                val tracked =
                    ConcurrencyLimit { ctx ->
                        limit.acquire(ctx).also { queued.complete(Unit) }
                    }
                Exchange(Route.DIRECT, tracked).use { exchange ->
                    val job =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            exchange.api.body(exchange.path).close()
                        }
                    withTimeout(5000) { queued.await() }
                    job.cancelAndJoin()
                    assertThat(exchange.dispatched.get()).isZero()
                    val replacement = limit.acquire(context)
                    assertThat(replacement).isNotDone()
                    held.close()
                    replacement.get(5, TimeUnit.SECONDS).close()
                    assertThat(exchange.dispatched.get()).isZero()
                }
            }
        }
    }

    private interface Api {
        @GET
        suspend fun body(
            @Url path: String,
        ): ResponseBody

        @GET
        suspend fun response(
            @Url path: String,
        ): Response<ResponseBody>

        @GET
        fun future(
            @Url path: String,
        ): CompletableFuture<ResponseBody>
    }

    private class Exchange(
        route: Route,
        limit: ConcurrencyLimit? = null,
    ) : AutoCloseable {
        val arrived = CompletableDeferred<Unit>()
        val transport = CompletableFuture<Throwable?>()
        val dispatched = AtomicInteger()
        private val response = CompletableFuture<HttpResponse>()
        private val server =
            Server
                .builder()
                .http(0)
                .service("/slow") { _, _ ->
                    arrived.complete(Unit)
                    HttpResponse.of(response)
                }.service("/redirect") { _, _ ->
                    HttpResponse.of(ResponseHeaders.of(HttpStatus.TEMPORARY_REDIRECT, "location", "/slow"))
                }.build()
        private val factory = ClientFactory.builder().build()
        val path = if (route.redirect) "/redirect" else "/slow"
        val api: Api

        init {
            server.start().join()
            val builder =
                WebClient
                    .builder("http://127.0.0.1:" + server.activeLocalPort())
                    .factory(factory)
                    .responseTimeoutMillis(0)
                    .decorator { delegate, ctx, req ->
                        dispatched.incrementAndGet()
                        val res = delegate.execute(ctx, req)
                        if (req.path() == "/slow") {
                            res.whenComplete().whenComplete { _, cause -> transport.complete(cause) }
                        }
                        res
                    }
            if (route.retry) builder.decorator(RetryingClient.newDecorator(RetryRule.onException()))
            if (route.redirect) builder.followRedirects()
            if (limit != null) builder.decorator(ConcurrencyLimitingClient.newDecorator(limit))
            api = ArmeriaRetrofit.builder(builder.build()).build().create(Api::class.java)
        }

        fun assertAborted() {
            assertThat(transport.get(5, TimeUnit.SECONDS)).isNotNull()
            assertThat(response).isNotDone()
        }

        fun complete() {
            response.complete(HttpResponse.of("ok"))
        }

        override fun close() {
            complete()
            factory.close()
            server.stop().join()
        }
    }
}
