/*
 * Copyright 2025 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.linecorp.armeria.server.kotlin

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.DuplicateRouteException
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.RejectedRouteHandler
import com.linecorp.armeria.server.RoutePathType
import com.linecorp.armeria.server.annotation.Get
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.function.Function
import java.util.stream.Collectors
import java.util.stream.Collectors.toSet

internal class HttpServerNestedContextPathTest {
    companion object {
        private const val VIRTUAL_HOSTNAME = "foo.com"

        @JvmField
        @RegisterExtension
        val server: ServerExtension =
            object : ServerExtension() {
                @Throws(Exception::class)
                override fun configure(sb: ServerBuilder) {
                    sb
                        .baseContextPath("/api")
                        .contextPath(ImmutableSet.of("/b1", "/b2")) { ctx1 ->
                            ctx1.annotatedService(
                                object {
                                    @Get("/svc1")
                                    fun depth1(): HttpResponse =
                                        HttpResponse.of(
                                            HttpStatus.OK,
                                            MediaType.PLAIN_TEXT_UTF_8,
                                            "/api/[b1|b2]/svc1",
                                        )
                                },
                            )

                            ctx1.contextPath(ImmutableSet.of("/c1", "/c2")) { ctx2 ->
                                ctx2.annotatedService(
                                    object {
                                        @Get("/svc2")
                                        fun depth2(): HttpResponse =
                                            HttpResponse.of(
                                                HttpStatus.OK,
                                                MediaType.PLAIN_TEXT_UTF_8,
                                                "/api/[b1|b2]/[c1/c2]/svc2",
                                            )
                                    },
                                )

                                ctx2.annotatedService(
                                    object {
                                        @Get("/svc3")
                                        fun depth2(): HttpResponse =
                                            HttpResponse.of(
                                                HttpStatus.OK,
                                                MediaType.PLAIN_TEXT_UTF_8,
                                                "/api/[b1|b2]/[c1/c2]/svc3",
                                            )
                                    },
                                )
                            }
                        }.contextPath(ImmutableSet.of("/d3", "/d4")) { ctx3 ->
                            ctx3.annotatedService(
                                object {
                                    @Get("/svc4")
                                    fun depth1(): HttpResponse =
                                        HttpResponse.of(
                                            HttpStatus.OK,
                                            MediaType.PLAIN_TEXT_UTF_8,
                                            "/api/[d3|d4]/svc4",
                                        )
                                },
                            )

                            ctx3.contextPath(ImmutableSet.of("/e3", "/e4")) { ctx4 ->
                                ctx4.service("/another/{id}/") { _, _ ->
                                    HttpResponse.of(HttpStatus.OK)
                                }
                                ctx4.service("/another/{id}") { _, _ ->
                                    HttpResponse.of(HttpStatus.NO_CONTENT)
                                }

                                ctx4.serviceUnder("/prefix-test/") { _, _ ->
                                    HttpResponse.of(HttpStatus.OK)
                                }

                                ctx4.annotatedService(
                                    object {
                                        @Get("/svc5")
                                        fun depth1(): HttpResponse =
                                            HttpResponse.of(
                                                HttpStatus.OK,
                                                MediaType.PLAIN_TEXT_UTF_8,
                                                "/api/[d3|d4]/[e3|e4]/svc5",
                                            )
                                    },
                                )
                            }
                        }.contextPath("/single-path1") { ctx1 ->
                            ctx1.contextPath("/single-path2") { ctx2 ->
                                ctx2.contextPath("/single-path3") { ctx3 ->
                                    ctx3.contextPath("/single-path4") { ctx4 ->
                                        ctx4.service("/hello") { _, _ ->
                                            HttpResponse.of(HttpStatus.OK)
                                        }
                                    }
                                }
                            }
                        }

                    sb
                        .virtualHost(VIRTUAL_HOSTNAME)
                        .contextPath(ImmutableSet.of("/k1", "/k2")) { ctx1 ->
                            ctx1.service("/hello") { _, _ ->
                                HttpResponse.of(HttpStatus.OK)
                            }
                            ctx1.service("/hello/") { _, _ ->
                                HttpResponse.of(HttpStatus.NO_CONTENT)
                            }
                            ctx1.contextPath(ImmutableSet.of("/q1", "/q2")) { ctx2 ->
                                ctx2.annotatedService(
                                    object {
                                        @Get("/svc5")
                                        fun svc5(): HttpResponse =
                                            HttpResponse.of(
                                                HttpStatus.OK,
                                                MediaType.PLAIN_TEXT_UTF_8,
                                                "svc5 response",
                                            )

                                        @Get("/svc6")
                                        fun svc6(): HttpResponse =
                                            HttpResponse.of(
                                                HttpStatus.OK,
                                                MediaType.PLAIN_TEXT_UTF_8,
                                                "svc6 response",
                                            )
                                    },
                                )

                                ctx2.serviceUnder("/prefix-match/") { _, _ ->
                                    HttpResponse.of(HttpStatus.OK)
                                }

                                ctx2.service("/exact-match-test/hello") { _, _ ->
                                    HttpResponse.of(HttpStatus.OK)
                                }

                                ctx2.serviceUnder("/exact-match-test/") { _, _ ->
                                    HttpResponse.of(HttpStatus.NO_CONTENT)
                                }
                            }
                        }
                }
            }

        private val TEST_URLS = LinkedHashMap<String, StatusCodeAndBody>()
        private val VIRTUAL_HOST_TEST_URLS = LinkedHashMap<String, StatusCodeAndBody>()

        init {
            TEST_URLS["/api/b1/svc1"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[b1|b2]/svc1")
            TEST_URLS["/api/b2/svc1"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[b1|b2]/svc1")

            TEST_URLS["/api/b1/c1/svc2"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc2")
            TEST_URLS["/api/b1/c2/svc2"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc2")
            TEST_URLS["/api/b2/c1/svc2"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc2")
            TEST_URLS["/api/b2/c2/svc2"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc2")

            TEST_URLS["/api/b1/c1/svc3"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc3")
            TEST_URLS["/api/b1/c2/svc3"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc3")
            TEST_URLS["/api/b2/c1/svc3"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc3")
            TEST_URLS["/api/b2/c2/svc3"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc3")

            TEST_URLS["/api/d3/svc4"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[d3|d4]/svc4")
            TEST_URLS["/api/d4/svc4"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[d3|d4]/svc4")

            TEST_URLS["/api/d3/e3/svc5"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[d3|d4]/[e3|e4]/svc5")
            TEST_URLS["/api/d3/e4/svc5"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[d3|d4]/[e3|e4]/svc5")
            TEST_URLS["/api/d4/e3/svc5"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[d3|d4]/[e3|e4]/svc5")
            TEST_URLS["/api/d4/e4/svc5"] =
                StatusCodeAndBody(HttpStatus.OK, "/api/[d3|d4]/[e3|e4]/svc5")

            TEST_URLS["/api/d3/e3/another/hello/"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            TEST_URLS["/api/d3/e3/another/hello"] =
                StatusCodeAndBody(HttpStatus.NO_CONTENT, "ignore body")

            TEST_URLS["/api/d3/e3/prefix-test/"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            TEST_URLS["/api/d3/e3/prefix-test/hello/"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            TEST_URLS["/api/d3/e3/prefix-test/////hello/"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            TEST_URLS["/api/d3/e3/prefix-test/hello..hello/foobar"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            TEST_URLS["/api/d3/e3/prefix-test////hello..hello////foobar"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            TEST_URLS["/api/d3/e3/prefix-test////foo::::::bar::::bar///hello"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            TEST_URLS["/api/d3/e3/prefix-test////foo>bar"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            TEST_URLS["/api/d3/e3/prefix-test////foo<bar"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            TEST_URLS["/api/d3/e3/prefix-test/\"?\""] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            TEST_URLS["/api/d3/e3/prefix-test/.\\"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")

            TEST_URLS["/api/d3/e3/prefix-test/.."] =
                StatusCodeAndBody(HttpStatus.BAD_REQUEST, "ignore body")
            TEST_URLS["/api/d3/e3/prefix-test/../"] =
                StatusCodeAndBody(HttpStatus.BAD_REQUEST, "ignore body")
            TEST_URLS["/api/d3/e3/prefix-test/../../../../"] =
                StatusCodeAndBody(HttpStatus.BAD_REQUEST, "ignore body")

            TEST_URLS["/api/b1"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b2"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b1/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b2/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b1/not_found"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b2/not_found"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b1/not_found/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b2/not_found/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")

            TEST_URLS["/api/b1/c1"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b1/c1/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b1/c2"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b1/c2/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")

            TEST_URLS["/api/b1/c1/not_found"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b1/c1/not_found/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b1/c1/svc3/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")

            TEST_URLS["/api/b1/c2/not_found"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b1/c2/not_found/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/api/b1/c2/svc3/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")

            TEST_URLS["/api/single-path1/single-path2/single-path3/single-path4/hello"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")

            // These routing path can be resolve in virtual host (foo.com)
            TEST_URLS["/k1/hello"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/k1/hello/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/k2/hello"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")
            TEST_URLS["/k2/hello/"] =
                StatusCodeAndBody(HttpStatus.NOT_FOUND, "ignore body")

            VIRTUAL_HOST_TEST_URLS["/k1/hello"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k1/hello/"] =
                StatusCodeAndBody(HttpStatus.NO_CONTENT, "ignore body")
            VIRTUAL_HOST_TEST_URLS["/k2/hello"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k2/hello/"] =
                StatusCodeAndBody(HttpStatus.NO_CONTENT, "ignore body")

            VIRTUAL_HOST_TEST_URLS["/k1/q1/svc5"] =
                StatusCodeAndBody(HttpStatus.OK, "svc5 response")
            VIRTUAL_HOST_TEST_URLS["/k1/q1/svc6"] =
                StatusCodeAndBody(HttpStatus.OK, "svc6 response")
            VIRTUAL_HOST_TEST_URLS["/k2/q1/svc5"] =
                StatusCodeAndBody(HttpStatus.OK, "svc5 response")
            VIRTUAL_HOST_TEST_URLS["/k2/q1/svc6"] =
                StatusCodeAndBody(HttpStatus.OK, "svc6 response")
            VIRTUAL_HOST_TEST_URLS["/k1/q2/svc5"] =
                StatusCodeAndBody(HttpStatus.OK, "svc5 response")
            VIRTUAL_HOST_TEST_URLS["/k1/q2/svc6"] =
                StatusCodeAndBody(HttpStatus.OK, "svc6 response")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/svc5"] =
                StatusCodeAndBody(HttpStatus.OK, "svc5 response")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/svc6"] =
                StatusCodeAndBody(HttpStatus.OK, "svc6 response")

            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match/"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match/hello/"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match/////hello/"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match/hello..hello/foobar"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match////hello..hello////foobar"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match////foo::::::bar::::bar///hello"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match////foo>bar"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match////foo<bar"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match/\"?\""] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match/.\\"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")

            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match/.."] =
                StatusCodeAndBody(HttpStatus.BAD_REQUEST, "ignore body")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match/../"] =
                StatusCodeAndBody(HttpStatus.BAD_REQUEST, "ignore body")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/prefix-match/../../../../"] =
                StatusCodeAndBody(HttpStatus.BAD_REQUEST, "ignore body")

            // Routing matching order in nested context path
            VIRTUAL_HOST_TEST_URLS["/k2/q2/exact-match-test/hello"] =
                StatusCodeAndBody(HttpStatus.OK, "200 OK")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/exact-match-test/"] =
                StatusCodeAndBody(HttpStatus.NO_CONTENT, "ignore body")
            VIRTUAL_HOST_TEST_URLS["/k2/q2/exact-match-test/unknown"] =
                StatusCodeAndBody(HttpStatus.NO_CONTENT, "ignore body")
        }
    }

    data class StatusCodeAndBody(
        val status: HttpStatus,
        val body: String,
    )

    @Test
    fun testNestedContextPathsWithOkResponse() {
        TEST_URLS.forEach { (path, expected) ->
            val res: AggregatedHttpResponse =
                WebClient
                    .of(server.httpUri())
                    .get(path)
                    .aggregate()
                    .join()

            assertThat(res.status()).isEqualTo(expected.status)
            if (res.status() == HttpStatus.OK) {
                assertThat(res.content(StandardCharsets.UTF_8)).isEqualTo(expected.body)
            }
        }
    }

    @Test
    fun testVirtualHostNestedContextPaths() {
        VIRTUAL_HOST_TEST_URLS.forEach { (path, expected) ->
            val res =
                WebClient
                    .builder(server.httpUri())
                    .addHeader(HttpHeaderNames.HOST, VIRTUAL_HOSTNAME)
                    .build()
                    .get(path)
                    .aggregate()
                    .join()

            assertThat(res.status()).isEqualTo(expected.status)
            if (res.status() == HttpStatus.OK) {
                assertThat(res.content(StandardCharsets.UTF_8)).isEqualTo(expected.body)
            }
        }
    }

    @Test
    fun emptyContextPathShouldBeFailed() {
        val serverBuilder =
            Server
                .builder()
                .baseContextPath("/api")
        assertThatThrownBy {
            serverBuilder.contextPath(ImmutableSet.of<String>()) { ctx1 ->
                ctx1.serviceUnder("/hello") { _, _ ->
                    HttpResponse.of(HttpStatus.OK)
                }
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun emptyNestedContextPathShouldBeFailed() {
        val serverBuilder = Server.builder().baseContextPath("/api")
        assertThatThrownBy {
            serverBuilder.contextPath(ImmutableSet.of("/hello")) { ctx1 ->
                ctx1.contextPath(ImmutableSet.of<String>()) { ctx2 ->
                    ctx2.serviceUnder("/hello") { _, _ ->
                        HttpResponse.of(HttpStatus.OK)
                    }
                }
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun relativeContextPathShouldBeFailed() {
        val serverBuilder = Server.builder().baseContextPath("/api")
        assertThatThrownBy {
            serverBuilder.contextPath(ImmutableSet.of("relative")) { ctx1 ->
                ctx1.serviceUnder("/hello") { _, _ ->
                    HttpResponse.of(HttpStatus.OK)
                }
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun relativeNestedContextPathShouldBeFailed() {
        val serverBuilder = Server.builder().baseContextPath("/api")
        assertThatThrownBy {
            serverBuilder.contextPath(ImmutableSet.of("/hello")) { ctx1 ->
                ctx1.contextPath(ImmutableSet.of("relative")) { ctx2 ->
                    ctx2.serviceUnder("/hello") { _, _ ->
                        HttpResponse.of(HttpStatus.OK)
                    }
                }
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun duplicatedContextPathShouldBeMerged() {
        val server =
            Server
                .builder()
                .baseContextPath("/api")
                .contextPath(ImmutableList.of("/b1", "/b1", "/b2")) { ctx ->
                    ctx.service("/svc") { _, _ ->
                        HttpResponse.of(HttpStatus.OK)
                    }
                }.build()

        val counts =
            server
                .config()
                .serviceConfigs()
                .stream()
                .flatMap { sc -> sc.route().paths().stream() }
                .collect(
                    Collectors.groupingBy(
                        Function.identity(),
                        Collectors.counting(),
                    ),
                )

        assertThat(counts)
            .containsEntry("/api/b1/svc", 2L)
            .containsEntry("/api/b2/svc", 2L)
            .hasSize(2)
    }

    @ParameterizedTest
    @CsvSource("/api", "/api/")
    fun noDoubleSlashInRoutes(basePath: String) {
        val server =
            Server
                .builder()
                .baseContextPath(basePath)
                .contextPath("/foo") { ctx ->
                    ctx.service("/bar") { _, _ ->
                        HttpResponse.of(HttpStatus.OK)
                    }
                }.build()

        val exactPath =
            server
                .config()
                .serviceConfigs()
                .stream()
                .filter { sc -> sc.route().pathType() == RoutePathType.EXACT }
                .flatMap { sc -> sc.route().paths().stream() }
                .collect(toSet())

        assertThat(exactPath).contains("/api/foo/bar")
        assertThat(exactPath).noneMatch { path -> path.contains("//") }
    }

    @ParameterizedTest
    @CsvSource("/pa", "/pa/")
    fun noDoubleSlashInNestedContextRoutes(nestedContextPath: String) {
        val server =
            Server
                .builder()
                .baseContextPath("/api")
                .contextPath("/foo") { ctx1 ->
                    ctx1.contextPath(ImmutableSet.of(nestedContextPath)) { ctx2 ->
                        ctx2.service("/bar") { _, _ ->
                            HttpResponse.of(HttpStatus.OK)
                        }
                    }
                }.build()

        val exactPath =
            server
                .config()
                .serviceConfigs()
                .stream()
                .filter { sc -> sc.route().pathType() == RoutePathType.EXACT }
                .flatMap { sc -> sc.route().paths().stream() }
                .collect(toSet())

        assertThat(exactPath).contains("/api/foo/pa/bar")
        assertThat(exactPath).noneMatch { path -> path.contains("//") }
    }

    @Test
    fun duplicate_path_should_be_failed_in_2depth_nested_context_with_server_builder() {
        assertThatThrownBy {
            Server
                .builder()
                .rejectedRouteHandler(RejectedRouteHandler.FAIL)
                .baseContextPath("/api")
                .contextPath("/foo") { ctx1 ->
                    ctx1.contextPath(ImmutableSet.of("/foo")) { ctx2 ->
                        ctx2.service("/bar") { _, _ ->
                            HttpResponse.of("duplicated1")
                        }
                    }

                    ctx1.contextPath(ImmutableSet.of("/foo")) { ctx2 ->
                        ctx2.service("/bar") { _, _ ->
                            HttpResponse.of("duplicated2")
                        }
                    }
                }.build()
        }.isInstanceOf(DuplicateRouteException::class.java)
    }

    @Test
    fun duplicate_path_should_be_failed_in_1depth_nested_context_with_server_builder() {
        assertThatThrownBy {
            Server
                .builder()
                .rejectedRouteHandler(RejectedRouteHandler.FAIL)
                .baseContextPath("/api")
                .contextPath(ImmutableSet.of("/foo", "/bar")) { ctx1 ->
                    ctx1.contextPath(ImmutableSet.of("/foo")) { ctx2 ->
                        ctx2.service("/bar") { _, _ ->
                            HttpResponse.of("duplicated1")
                        }
                    }

                    ctx1.contextPath(ImmutableSet.of("/foo")) { ctx2 ->
                        ctx2.service("/bar") { _, _ ->
                            HttpResponse.of("duplicated2")
                        }
                    }
                }.contextPath("/bar") { ctx1 ->
                    ctx1.contextPath("/foo") { ctx2 ->
                        ctx2.service("/bar") { _, _ ->
                            HttpResponse.of("duplicated1")
                        }
                    }
                }.build()
        }.isInstanceOf(DuplicateRouteException::class.java)
    }

    @Test
    fun duplicate_path_should_be_failed_in_2depth_nested_context_with_virtual_host_builder() {
        assertThatThrownBy {
            Server
                .builder()
                .virtualHost("foo.com")
                .rejectedRouteHandler(RejectedRouteHandler.FAIL)
                .baseContextPath("/api")
                .contextPath("/foo") { ctx1 ->
                    ctx1.contextPath(ImmutableSet.of("/foo")) { ctx2 ->
                        ctx2.service("/bar") { _, _ ->
                            HttpResponse.of("duplicated1")
                        }
                    }

                    ctx1.contextPath(ImmutableSet.of("/foo")) { ctx2 ->
                        ctx2.service("/bar") { _, _ ->
                            HttpResponse.of("duplicated2")
                        }
                    }
                }.and()
                .build()
        }.isInstanceOf(DuplicateRouteException::class.java)
    }

    @Test
    fun duplicate_path_should_be_failed_in_1depth_nested_context_with_virtual_host_builder() {
        assertThatThrownBy {
            Server
                .builder()
                .virtualHost("foo.com")
                .rejectedRouteHandler(RejectedRouteHandler.FAIL)
                .baseContextPath("/api")
                .contextPath(ImmutableSet.of("/foo", "/bar")) { ctx1 ->
                    ctx1.contextPath(ImmutableSet.of("/foo")) { ctx2 ->
                        ctx2.service("/bar") { _, _ ->
                            HttpResponse.of("duplicated1")
                        }
                    }

                    ctx1.contextPath(ImmutableSet.of("/foo")) { ctx2 ->
                        ctx2.service("/bar") { _, _ ->
                            HttpResponse.of("duplicated2")
                        }
                    }
                }.contextPath("/bar") { ctx1 ->
                    ctx1.contextPath("/foo") { ctx2 ->
                        ctx2.service("/bar") { _, _ ->
                            HttpResponse.of("duplicated1")
                        }
                    }
                }.and()
                .build()
        }.isInstanceOf(DuplicateRouteException::class.java)
    }
}
