/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.kotlin

import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

@GenerateNativeImageTrace
class CoroutineRestClientTest {
    @Test
    fun get() {
        runBlocking {
            val restClient = server.restClient()
            val response =
                restClient
                    .get("/rest/{id}")
                    .pathParam("id", "1")
                    .execute<RestResponse>()
            val content: RestResponse = response.content()
            assertThat(content.id).isEqualTo("1")
            assertThat(content.method).isEqualTo("GET")
            assertThat(content.content).isEqualTo("")
        }
    }

    @Test
    fun post() {
        runBlocking {
            val restClient = server.restClient()
            val response =
                restClient
                    .post("/rest/{id}")
                    .pathParam("id", "1")
                    .contentJson("content")
                    .execute<RestResponse>()
            val content: RestResponse = response.content()
            assertThat(content.id).isEqualTo("1")
            assertThat(content.method).isEqualTo("POST")
            assertThat(content.content).isEqualTo("\"content\"")
        }
    }

    companion object {
        @RegisterExtension
        var server: ServerExtension =
            object : ServerExtension() {
                override fun configure(sb: ServerBuilder) {
                    sb.service("/rest/{id}") { ctx: ServiceRequestContext, req: HttpRequest ->
                        HttpResponse.of(
                            req.aggregate().thenApply { agg: AggregatedHttpRequest ->
                                val restResponse =
                                    RestResponse(
                                        ctx.pathParam("id")!!,
                                        req.method().toString(),
                                        agg.contentUtf8(),
                                    )
                                HttpResponse.ofJson(restResponse)
                            },
                        )
                    }
                }
            }
    }

    data class RestResponse(val id: String, val method: String, val content: String)
}
