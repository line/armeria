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

package com.linecorp.armeria.server.kotlin

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Post
import com.linecorp.armeria.server.annotation.RequestConverter
import com.linecorp.armeria.server.annotation.RequestConverterFunction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.lang.reflect.ParameterizedType

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NullableValueClassTest {
    private lateinit var server: Server
    private lateinit var client: WebClient

    @BeforeAll
    fun startServer() {
        server =
            Server
                .builder()
                .http(0)
                .annotatedService(Service())
                .build()
        server.start().join()
        client = WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
    }

    @AfterAll
    fun stopServer() {
        server.stop().join()
    }

    @ParameterizedTest
    @CsvSource(
        "/plain, ok",
        "/nullable, missing",
        "/nullable?id=7, 7",
        "/sync-nullable, missing",
        "/sync-nullable?id=7, 7",
        "/ordinary-nullable, missing",
        "/ordinary-nullable?value=ok, ok",
    )
    fun shouldBindNullableArguments(
        path: String,
        expected: String,
    ) {
        val response = client.get(path).aggregate().join()
        assertThat(response.status().code()).isEqualTo(200)
        assertThat(response.contentUtf8()).isEqualTo(expected)
    }

    @Test
    fun shouldKeepUnboxedNonNullableValueClassSupport() {
        val response =
            client
                .prepare()
                .post("/required-payload")
                .content(MediaType.JSON, """{"hello":"ok"}""")
                .execute()
                .aggregate()
                .join()
        assertThat(response.status().code()).isEqualTo(200)
        assertThat(response.contentUtf8()).isEqualTo("ok")
    }

    @JvmInline
    value class Id(
        val value: Int,
    )

    data class Body(
        val hello: String,
    )

    @JvmInline
    value class Payload(
        val body: Body,
    )

    class IdConverter : RequestConverterFunction {
        override fun convertRequest(
            ctx: ServiceRequestContext,
            request: AggregatedHttpRequest,
            expectedResultType: Class<*>,
            expectedParameterizedResultType: ParameterizedType?,
        ): Any? {
            if (expectedResultType == Id::class.java) {
                return ctx.queryParam("id")?.toInt()?.let { Id(it) }
            }
            return RequestConverterFunction.fallthrough()
        }
    }

    class StringConverter : RequestConverterFunction {
        override fun convertRequest(
            ctx: ServiceRequestContext,
            request: AggregatedHttpRequest,
            expectedResultType: Class<*>,
            expectedParameterizedResultType: ParameterizedType?,
        ): Any? {
            if (expectedResultType == String::class.java) return ctx.queryParam("value")
            return RequestConverterFunction.fallthrough()
        }
    }

    class Service {
        @Get("/plain")
        fun plain() = "ok"

        @Get("/nullable")
        @RequestConverter(IdConverter::class)
        suspend fun nullable(id: Id?) = id?.value?.toString() ?: "missing"

        @Get("/sync-nullable")
        @RequestConverter(IdConverter::class)
        fun syncNullable(id: Id?) = id?.value?.toString() ?: "missing"

        @Get("/ordinary-nullable")
        @RequestConverter(StringConverter::class)
        suspend fun ordinaryNullable(value: String?) = value ?: "missing"

        @Post("/required-payload")
        suspend fun requiredPayload(input: Payload) = input.body.hello
    }
}
