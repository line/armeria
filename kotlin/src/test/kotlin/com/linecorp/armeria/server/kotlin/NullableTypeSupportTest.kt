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
 * under the License
 */

package com.linecorp.armeria.server.kotlin

import com.linecorp.armeria.common.AggregatedHttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.QueryParams
import com.linecorp.armeria.common.annotation.Nullable
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.server.annotation.Param
import com.linecorp.armeria.server.annotation.RequestConverter
import com.linecorp.armeria.server.annotation.RequestConverterFunction
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.lang.reflect.ParameterizedType

@GenerateNativeImageTrace
class NullableTypeSupportTest {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "/of-query-param",
            "/of-request-converter",
            "/of-bean-constructor",
            "/of-bean-field",
            "/of-bean-method",
        ],
    )
    fun test_nullableParameters(testPath: String) {
        testNullableParameters("/nullable-type/value-resolver/$testPath")
        testNullableParameters("/nullable-type/value-resolver/suspend/$testPath")

        // Check for backward-compatibility
        testNullableParameters("/nullable-annot/value-resolver/$testPath")
        testNullableParameters("/nullable-annot/value-resolver/suspend/$testPath")
    }

    private fun testNullableParameters(testPath: String) {
        val client = server.blockingWebClient()

        with(client.get(testPath, QueryParams.of("a", "a"))) {
            assertThat(status()).isEqualTo(HttpStatus.OK)
            assertThat(contentUtf8()).isEqualTo("a: a, b: null")
        }
        with(client.get(testPath, QueryParams.of("a", "a", "b", "b"))) {
            assertThat(status()).isEqualTo(HttpStatus.OK)
            assertThat(contentUtf8()).isEqualTo("a: a, b: b")
        }
        with(client.get(testPath, QueryParams.of("b", "b"))) {
            assertThat(status()).isEqualTo(HttpStatus.BAD_REQUEST)
        }
        with(client.get(testPath)) {
            assertThat(status()).isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    companion object {
        @JvmField
        @RegisterExtension
        val server =
            object : ServerExtension() {
                override fun configure(sb: ServerBuilder) {
                    sb.apply {
                        annotatedService(
                            "/nullable-type/value-resolver",
                            object {
                                @Get("/of-query-param")
                                fun ofQueryParam(
                                    @Param a: String,
                                    @Param b: String?,
                                ) = HttpResponse.of("a: $a, b: $b")

                                @Get("/suspend/of-query-param")
                                suspend fun suspendOfQueryParam(
                                    @Param a: String,
                                    @Param b: String?,
                                ) = HttpResponse.of("a: $a, b: $b")

                                @Get("/of-request-converter")
                                @RequestConverter(FooBarRequestConverter::class)
                                fun ofRequestConverter(
                                    foo: Foo,
                                    bar: Bar?,
                                ) = HttpResponse.of("a: ${foo.value}, b: ${bar?.value}")

                                @Get("/suspend/of-request-converter")
                                @RequestConverter(FooBarRequestConverter::class)
                                suspend fun suspendOfRequestConverter(
                                    foo: Foo,
                                    bar: Bar?,
                                ) = HttpResponse.of("a: ${foo.value}, b: ${bar?.value}")

                                @Get("/of-bean-constructor")
                                fun ofBeanConstructor(baz: Baz) = HttpResponse.of("a: ${baz.a}, b: ${baz.b}")

                                @Get("/suspend/of-bean-constructor")
                                suspend fun suspendOfBeanConstructor(baz: Baz) =
                                    HttpResponse.of("a: ${baz.a}, b: ${baz.b}")

                                @Get("/of-bean-field")
                                fun ofBeanField(qux: Qux) = HttpResponse.of("a: ${qux.a}, b: ${qux.b}")

                                @Get("/suspend/of-bean-field")
                                suspend fun suspendOfBeanField(qux: Qux) =
                                    HttpResponse.of("a: ${qux.a}, b: ${qux.b}")

                                @Get("/of-bean-method")
                                fun ofBeanMethod(quux: Quux) = HttpResponse.of("a: ${quux.a}, b: ${quux.b}")

                                @Get("/suspend/of-bean-method")
                                suspend fun suspendOfBeanMethod(quux: Quux) =
                                    HttpResponse.of("a: ${quux.a}, b: ${quux.b}")
                            },
                        )
                        sb.annotatedService(
                            "/nullable-annot/value-resolver",
                            object {
                                @Get("/of-query-param")
                                fun ofQueryParam(
                                    @Param a: String,
                                    @Nullable
                                    @Param
                                    b: String?,
                                ) = HttpResponse.of("a: $a, b: $b")

                                @Get("/suspend/of-query-param")
                                suspend fun suspendOfQueryParam(
                                    @Param a: String,
                                    @Nullable
                                    @Param
                                    b: String?,
                                ) = HttpResponse.of("a: $a, b: $b")

                                @Get("/of-request-converter")
                                @RequestConverter(FooBarRequestConverter::class)
                                fun ofRequestConverter(
                                    foo: Foo,
                                    @Nullable bar: Bar?,
                                ) = HttpResponse.of("a: ${foo.value}, b: ${bar?.value}")

                                @Get("/suspend/of-request-converter")
                                @RequestConverter(FooBarRequestConverter::class)
                                suspend fun suspendOfRequestConverter(
                                    foo: Foo,
                                    @Nullable bar: Bar?,
                                ) = HttpResponse.of("a: ${foo.value}, b: ${bar?.value}")

                                @Get("/of-bean-constructor")
                                fun ofBeanConstructor(baz: Baz0) = HttpResponse.of("a: ${baz.a}, b: ${baz.b}")

                                @Get("/suspend/of-bean-constructor")
                                suspend fun suspendOfBeanConstructor(baz: Baz0) =
                                    HttpResponse.of("a: ${baz.a}, b: ${baz.b}")

                                @Get("/of-bean-field")
                                fun ofBeanField(qux: Qux0) = HttpResponse.of("a: ${qux.a}, b: ${qux.b}")

                                @Get("/suspend/of-bean-field")
                                suspend fun suspendOfBeanField(qux: Qux0) =
                                    HttpResponse.of("a: ${qux.a}, b: ${qux.b}")

                                @Get("/of-bean-method")
                                fun ofBeanMethod(quux: Quux0) = HttpResponse.of("a: ${quux.a}, b: ${quux.b}")

                                @Get("/suspend/of-bean-method")
                                suspend fun suspendOfBeanMethod(quux: Quux0) =
                                    HttpResponse.of("a: ${quux.a}, b: ${quux.b}")
                            },
                        )
                    }
                }
            }

        data class Foo(
            val value: String,
        )

        data class Bar(
            val value: String,
        )

        class Baz(
            @Param("a") val a: String,
            @Param("b") val b: String?,
        )

        // Check for backward-compatibility
        class Baz0(
            @Param("a") val a: String,
            @Nullable
            @Param("b")
            val b: String?,
        )

        class Qux {
            @Param("a")
            lateinit var a: String

            @Param("b")
            var b: String? = null
        }

        class Qux0 {
            @Param("a")
            lateinit var a: String

            @Nullable
            @Param("b")
            var b: String? = null
        }

        class Quux {
            lateinit var a: String
            var b: String? = null

            fun setter(
                @Param("a") a: String,
                @Param("b") b: String?,
            ) {
                this.a = a
                this.b = b
            }
        }

        // Check for backward-compatibility
        class Quux0 {
            lateinit var a: String
            var b: String? = null

            fun setter(
                @Param("a") a: String,
                @Nullable
                @Param("b")
                b: String?,
            ) {
                this.a = a
                this.b = b
            }
        }

        class FooBarRequestConverter : RequestConverterFunction {
            override fun convertRequest(
                ctx: ServiceRequestContext,
                request: AggregatedHttpRequest,
                expectedResultType: Class<*>,
                expectedParameterizedResultType: ParameterizedType?,
            ): Any? {
                if (expectedResultType.isAssignableFrom(Foo::class.java)) {
                    return ctx.queryParam("a")?.let { Foo(it) }
                }
                if (expectedResultType.isAssignableFrom(Bar::class.java)) {
                    return ctx.queryParam("b")?.let { Bar(it) }
                }
                throw RequestConverterFunction.fallthrough()
            }
        }
    }
}
