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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

public class HttpServerNestedContextPathTest {

    static final String VIRTUAL_HOSTNAME = "foo.com";

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            sb.baseContextPath("/api")
              .contextPath(ImmutableSet.of("/b1", "/b2"), ctx1 -> {
                  ctx1.annotatedService(new Object() {
                      @Get("/svc1")
                      public HttpResponse depth1() {
                          return HttpResponse.of(HttpStatus.OK,
                                                 MediaType.PLAIN_TEXT_UTF_8,
                                                 "/api/[b1|b2]/svc1");
                      }
                  });

                  ctx1.contextPath(ImmutableSet.of("/c1", "/c2"), ctx2 -> {
                      ctx2.annotatedService(new Object() {
                          @Get("/svc2")
                          public HttpResponse depth2() {
                              return HttpResponse.of(HttpStatus.OK,
                                                     MediaType.PLAIN_TEXT_UTF_8,
                                                     "/api/[b1|b2]/[c1/c2]/svc2");
                          }
                      });

                      ctx2.annotatedService(new Object() {
                          @Get("/svc3")
                          public HttpResponse depth2() {
                              return HttpResponse.of(HttpStatus.OK,
                                                     MediaType.PLAIN_TEXT_UTF_8,
                                                     "/api/[b1|b2]/[c1/c2]/svc3");
                          }
                      });
                  });
              })
              .contextPath(ImmutableSet.of("/d3", "/d4"), ctx3 -> {
                  ctx3.annotatedService(new Object() {
                      @Get("/svc4")
                      public HttpResponse depth1() {
                          return HttpResponse.of(HttpStatus.OK,
                                                 MediaType.PLAIN_TEXT_UTF_8,
                                                 "/api/[d3|d4]/svc4");
                      }
                  });

                  ctx3.contextPath(ImmutableSet.of("/e3", "/e4"), ctx4 -> {

                      ctx4.service("/another/{id}/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
                      ctx4.service("/another/{id}", (ctx, req) -> HttpResponse.of(HttpStatus.NO_CONTENT));

                      ctx4.serviceUnder("/prefix-test/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));

                      ctx4.annotatedService(new Object() {
                          @Get("/svc5")
                          public HttpResponse depth1() {
                              return HttpResponse.of(HttpStatus.OK,
                                                     MediaType.PLAIN_TEXT_UTF_8,
                                                     "/api/[d3|d4]/[e3|e4]/svc5");
                          }
                      });
                  });
              })
              .contextPath("/single-path1", ctx1 -> {
                  ctx1.contextPath("/single-path2", ctx2 -> {
                      ctx2.contextPath("/single-path3", ctx3 -> {
                          ctx3.contextPath("/single-path4", ctx4 -> {
                              ctx4.service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
                          });
                      });
                  });
              });

            sb.virtualHost(VIRTUAL_HOSTNAME)
              .contextPath(ImmutableSet.of("/k1", "/k2"), ctx1 -> {
                  ctx1.service("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
                  ctx1.service("/hello/", (ctx, req) -> HttpResponse.of(HttpStatus.NO_CONTENT));

                  ctx1.contextPath(ImmutableSet.of("/q1", "/q2"), ctx2 -> {
                      ctx2.annotatedService(new Object() {
                          @Get("/svc5")
                          public HttpResponse svc5() {
                              return HttpResponse.of(HttpStatus.OK,
                                                     MediaType.PLAIN_TEXT_UTF_8,
                                                     "svc5 response");
                          }

                          @Get("/svc6")
                          public HttpResponse svc6() {
                              return HttpResponse.of(HttpStatus.OK,
                                                     MediaType.PLAIN_TEXT_UTF_8,
                                                     "svc6 response");
                          }
                      });

                      ctx2.serviceUnder("/prefix-match/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));

                      ctx2.service("/exact-match-test/hello", (c, r) -> HttpResponse.of(HttpStatus.OK));
                      ctx2.serviceUnder("/exact-match-test/", (c, r) -> HttpResponse.of(HttpStatus.NO_CONTENT));
                  });
              });
        }
    };

    private static final Map<String, StatusCodeAndBody> TEST_URLS = new LinkedHashMap<>();

    private static final Map<String, StatusCodeAndBody> VIRTUAL_HOST_TEST_URLS = new LinkedHashMap<>();

    static {
        TEST_URLS.put("/api/b1/svc1",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[b1|b2]/svc1"));
        TEST_URLS.put("/api/b2/svc1",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[b1|b2]/svc1"));

        TEST_URLS.put("/api/b1/c1/svc2",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc2"));
        TEST_URLS.put("/api/b1/c2/svc2",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc2"));
        TEST_URLS.put("/api/b2/c1/svc2",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc2"));
        TEST_URLS.put("/api/b2/c2/svc2",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc2"));

        TEST_URLS.put("/api/b1/c1/svc3",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc3"));
        TEST_URLS.put("/api/b1/c2/svc3",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc3"));
        TEST_URLS.put("/api/b2/c1/svc3",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc3"));
        TEST_URLS.put("/api/b2/c2/svc3",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[b1|b2]/[c1/c2]/svc3"));

        TEST_URLS.put("/api/d3/svc4",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[d3|d4]/svc4"));
        TEST_URLS.put("/api/d4/svc4",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[d3|d4]/svc4"));

        TEST_URLS.put("/api/d3/e3/svc5",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[d3|d4]/[e3|e4]/svc5"));
        TEST_URLS.put("/api/d3/e4/svc5",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[d3|d4]/[e3|e4]/svc5"));
        TEST_URLS.put("/api/d4/e3/svc5",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[d3|d4]/[e3|e4]/svc5"));
        TEST_URLS.put("/api/d4/e4/svc5",
                      StatusCodeAndBody.of(HttpStatus.OK, "/api/[d3|d4]/[e3|e4]/svc5"));

        TEST_URLS.put("/api/d3/e3/another/hello/",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        TEST_URLS.put("/api/d3/e3/another/hello",
                      StatusCodeAndBody.of(HttpStatus.NO_CONTENT, "ignore body"));

        TEST_URLS.put("/api/d3/e3/prefix-test/",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        TEST_URLS.put("/api/d3/e3/prefix-test/hello/",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        TEST_URLS.put("/api/d3/e3/prefix-test/////hello/",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        TEST_URLS.put("/api/d3/e3/prefix-test/hello..hello/foobar",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        TEST_URLS.put("/api/d3/e3/prefix-test////hello..hello////foobar",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        TEST_URLS.put("/api/d3/e3/prefix-test////foo::::::bar::::bar///hello",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        TEST_URLS.put("/api/d3/e3/prefix-test////foo>bar",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        TEST_URLS.put("/api/d3/e3/prefix-test////foo<bar",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        TEST_URLS.put("/api/d3/e3/prefix-test/\"?\"",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        TEST_URLS.put("/api/d3/e3/prefix-test/.\\",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));

        TEST_URLS.put("/api/d3/e3/prefix-test/..",
                      StatusCodeAndBody.of(HttpStatus.BAD_REQUEST, "ignore body"));
        TEST_URLS.put("/api/d3/e3/prefix-test/../",
                      StatusCodeAndBody.of(HttpStatus.BAD_REQUEST, "ignore body"));
        TEST_URLS.put("/api/d3/e3/prefix-test/../../../../",
                      StatusCodeAndBody.of(HttpStatus.BAD_REQUEST, "ignore body"));

        TEST_URLS.put("/api/b1",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b2",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b1/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b2/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b1/not_found",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b2/not_found",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b1/not_found/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b2/not_found/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));

        TEST_URLS.put("/api/b1/c1",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b1/c1/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b1/c2",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b1/c2/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));

        TEST_URLS.put("/api/b1/c1/not_found",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b1/c1/not_found/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b1/c1/svc3/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));

        TEST_URLS.put("/api/b1/c2/not_found",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b1/c2/not_found/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/api/b1/c2/svc3/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));

        TEST_URLS.put("/api/single-path1/single-path2/single-path3/single-path4/hello",
                      StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));

        // These routing path can be resolve in virtual host (foo.com)
        TEST_URLS.put("/k1/hello",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/k1/hello/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/k2/hello",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));
        TEST_URLS.put("/k2/hello/",
                      StatusCodeAndBody.of(HttpStatus.NOT_FOUND, "ignore body"));

        VIRTUAL_HOST_TEST_URLS.put("/k1/hello",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k1/hello/",
                                   StatusCodeAndBody.of(HttpStatus.NO_CONTENT, "ignore body"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/hello",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/hello/",
                                   StatusCodeAndBody.of(HttpStatus.NO_CONTENT, "ignore body"));

        VIRTUAL_HOST_TEST_URLS.put("/k1/q1/svc5",
                                   StatusCodeAndBody.of(HttpStatus.OK, "svc5 response"));
        VIRTUAL_HOST_TEST_URLS.put("/k1/q1/svc6",
                                   StatusCodeAndBody.of(HttpStatus.OK, "svc6 response"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q1/svc5",
                                   StatusCodeAndBody.of(HttpStatus.OK, "svc5 response"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q1/svc6",
                                   StatusCodeAndBody.of(HttpStatus.OK, "svc6 response"));
        VIRTUAL_HOST_TEST_URLS.put("/k1/q2/svc5",
                                   StatusCodeAndBody.of(HttpStatus.OK, "svc5 response"));
        VIRTUAL_HOST_TEST_URLS.put("/k1/q2/svc6",
                                   StatusCodeAndBody.of(HttpStatus.OK, "svc6 response"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/svc5",
                                   StatusCodeAndBody.of(HttpStatus.OK, "svc5 response"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/svc6",
                                   StatusCodeAndBody.of(HttpStatus.OK, "svc6 response"));

        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match/",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match/hello/",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match/////hello/",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match/hello..hello/foobar",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match////hello..hello////foobar",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match////foo::::::bar::::bar///hello",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match////foo>bar",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match////foo<bar",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match/\"?\"",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match/.\\",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));

        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match/..",
                                   StatusCodeAndBody.of(HttpStatus.BAD_REQUEST, "ignore body"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match/../",
                                   StatusCodeAndBody.of(HttpStatus.BAD_REQUEST, "ignore body"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/prefix-match/../../../../",
                                   StatusCodeAndBody.of(HttpStatus.BAD_REQUEST, "ignore body"));

        // Routing matching order in nested context path
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/exact-match-test/hello",
                                   StatusCodeAndBody.of(HttpStatus.OK, "200 OK"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/exact-match-test/",
                                   StatusCodeAndBody.of(HttpStatus.NO_CONTENT, "ignore body"));
        VIRTUAL_HOST_TEST_URLS.put("/k2/q2/exact-match-test/unknown",
                                   StatusCodeAndBody.of(HttpStatus.NO_CONTENT, "ignore body"));
    }

    @Test
    void testNestedContextPathsWithOkResponse()  {
        for (Entry<String, StatusCodeAndBody> url : TEST_URLS.entrySet()) {
            final AggregatedHttpResponse res = WebClient.of(server.httpUri())
                                                        .get(url.getKey())
                                                        .aggregate()
                                                        .join();
            final StatusCodeAndBody expectedRes = url.getValue();

            assertThat(res.status()).isEqualTo(expectedRes.status);

            if (res.status() == HttpStatus.OK) {
                assertThat(res.content(StandardCharsets.UTF_8)).isEqualTo(expectedRes.body);
            }
        }
    }

    @Test
    void testVirtualHostNestedContextPaths() {
        for (Entry<String, StatusCodeAndBody> url : VIRTUAL_HOST_TEST_URLS.entrySet()) {
            final AggregatedHttpResponse res = WebClient.builder(server.httpUri())
                                                        .addHeader(HttpHeaderNames.HOST, VIRTUAL_HOSTNAME)
                                                        .build()
                                                        .get(url.getKey())
                                                        .aggregate()
                                                        .join();
            final StatusCodeAndBody expectedRes = url.getValue();
            assertThat(res.status()).isEqualTo(expectedRes.status);

            if (res.status() == HttpStatus.OK) {
                assertThat(res.content(StandardCharsets.UTF_8)).isEqualTo(expectedRes.body);
            }
        }
    }

    @Test
    void emptyContextPathShouldBeFailed() {
        final ServerBuilder serverBuilder = new ServerBuilder().baseContextPath("/api");
        assertThatThrownBy(() -> {
            serverBuilder.contextPath(ImmutableSet.of(), ctx1 -> {
                ctx1.serviceUnder("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            });
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyNestedContextPathShouldBeFailed() {
        final ServerBuilder serverBuilder = new ServerBuilder().baseContextPath("/api");
        assertThatThrownBy(() -> {
            serverBuilder.contextPath(ImmutableSet.of("/hello"), ctx1 -> {
                ctx1.contextPath(ImmutableSet.of(), ctx2 -> {
                    ctx2.serviceUnder("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
                });
            });
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void relativeContextPathShouldBeFailed() {
        final ServerBuilder serverBuilder = new ServerBuilder().baseContextPath("/api");
        assertThatThrownBy(() -> {
            serverBuilder.contextPath(ImmutableSet.of("relative"), ctx1 -> {
                ctx1.serviceUnder("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            });
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void relativeNestedContextPathShouldBeFailed() {
        final ServerBuilder serverBuilder = new ServerBuilder().baseContextPath("/api");
        assertThatThrownBy(() -> {
            serverBuilder.contextPath(ImmutableSet.of("/hello"), ctx1 -> {
                ctx1.contextPath(ImmutableSet.of("relative"), ctx2 -> {
                    ctx2.serviceUnder("/hello", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
                });
            });
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicatedContextPathShouldbeMerged() {
        final Server server = new ServerBuilder()
                .baseContextPath("/api")
                .contextPath(ImmutableList.of("/b1", "/b1", "/b2"), ctx -> {
                    ctx.service("/svc", (c, r) -> HttpResponse.of(HttpStatus.OK));
                })
                .build();

        final Map<String, Long> counts = server.config().serviceConfigs().stream()
                                               .flatMap(sc -> sc.route().paths().stream())
                                               .collect(
                                                       Collectors.groupingBy(
                                                               Function.identity(),
                                                               Collectors.counting()
                                                       ));

        assertThat(counts).containsEntry("/api/b1/svc", 2L)
                          .containsEntry("/api/b2/svc", 2L)
                          .hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({ "/api", "/api/"})
    void noDoubleSlashInRoutes(String basePath) {
        final Server server = new ServerBuilder()
                .baseContextPath(basePath)
                .contextPath("/foo", ctx -> {
                    ctx.service("/bar", (c, r) -> HttpResponse.of(HttpStatus.OK));
                }).build();

        final Set<String> exactPath = server.config().serviceConfigs().stream()
                                    .filter(sc -> sc.route().pathType() == RoutePathType.EXACT)
                                    .flatMap(sc -> sc.route().paths().stream())
                                    .collect(toSet());
        assertThat(exactPath).contains("/api/foo/bar");
        assertThat(exactPath).noneMatch(path -> path.contains("//"));
    }

    @ParameterizedTest
    @CsvSource({ "/pa", "/pa/"})
    void noDoubleSlashInNestedContextRoutes(String nestedContextPath) {
        final Server server = new ServerBuilder()
                .baseContextPath("/api")
                .contextPath("/foo", ctx1 -> {
                    ctx1.contextPath(ImmutableSet.of(nestedContextPath), ctx2 -> {
                        ctx2.service("/bar", (c, r) -> HttpResponse.of(HttpStatus.OK));
                    });
                }).build();

        final Set<String> exactPath = server.config().serviceConfigs().stream()
                                            .filter(sc -> sc.route().pathType() == RoutePathType.EXACT)
                                            .flatMap(sc -> sc.route().paths().stream())
                                            .collect(toSet());
        assertThat(exactPath).contains("/api/foo/pa/bar");
        assertThat(exactPath).noneMatch(path -> path.contains("//"));
    }

    private static final class StatusCodeAndBody {

        final HttpStatus status;
        final String body;

        private StatusCodeAndBody(HttpStatus status, String body) {
            this.status = status;
            this.body = body;
        }

        static StatusCodeAndBody of(HttpStatus status, String body) {
            return new StatusCodeAndBody(status, body);
        }
    }
}
