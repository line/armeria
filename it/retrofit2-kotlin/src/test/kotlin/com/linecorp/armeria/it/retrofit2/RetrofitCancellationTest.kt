/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.it.retrofit2

import com.linecorp.armeria.client.ClientFactory
import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofit
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.SessionProtocol
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import retrofit2.http.GET
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RetrofitCancellationTest {
    private interface Service {
        @GET("/hang")
        suspend fun get(): ResponseBody
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol::class, names = ["H1C", "H2C"])
    fun cancellationAbortsServerRequest(protocol: SessionProtocol) {
        val exchange = Exchange()
        current.set(exchange)
        ClientFactory.builder().build().use { factory ->
            val service =
                ArmeriaRetrofit
                    .builder(server.uri(protocol))
                    .factory(factory)
                    .responseTimeoutMillis(0)
                    .build()
                    .create(Service::class.java)
            try {
                runBlocking {
                    val call = launch(Dispatchers.Default) { service.get().close() }
                    val context = exchange.started.get(5, TimeUnit.SECONDS)
                    call.cancelAndJoin()
                    assertThat(call.isCancelled).isTrue()
                    assertThat(
                        context
                            .log()
                            .whenComplete()
                            .get(3, TimeUnit.SECONDS)
                            .responseCause(),
                    ).isNotNull()
                }
            } finally {
                exchange.response.abort()
            }
        }
    }

    private class Exchange {
        val response = HttpResponse.streaming()
        val started = CompletableFuture<ServiceRequestContext>()
    }

    companion object {
        private val current = AtomicReference<Exchange>()

        @JvmField
        @RegisterExtension
        val server: ServerExtension =
            object : ServerExtension() {
                override fun configure(sb: ServerBuilder) {
                    sb.requestTimeoutMillis(0)
                    sb.service("/hang") { context, _ ->
                        val exchange = current.get()
                        exchange.started.complete(context)
                        exchange.response
                    }
                }
            }
    }
}
