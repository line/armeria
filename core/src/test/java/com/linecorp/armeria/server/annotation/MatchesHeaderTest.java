/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MatchesHeaderTest {

    @RegisterExtension
    public static final ServerExtension extension = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {
                @Get("/matches")
                @MatchesHeader("my-header=my-value")
                public String matches() {
                    return "my-header=my-value";
                }

                @Get("/matches")
                public String fallback1() {
                    return "fallback";
                }

                @Get("/doesNotMatch")
                @MatchesHeader("my-header!=my-value")
                public String doesNotMatch() {
                    return "my-header!=my-value";
                }

                @Get("/doesNotMatch")
                public String fallback2() {
                    return "fallback";
                }

                @Get("/contains")
                @MatchesHeader("my-header")
                public String contains() {
                    return "my-header";
                }

                @Get("/contains")
                public String fallback3() {
                    return "fallback";
                }

                @Get("/doesNotContain")
                @MatchesHeader("!my-header")
                public String doesNotContain() {
                    return "!my-header";
                }

                @Get("/doesNotContain")
                public String fallback4() {
                    return "fallback";
                }

                @Get("/multiple")
                @MatchesHeader("my-header=my-value")
                @MatchesHeader("!your-header")
                public String multiple() {
                    return "multiple";
                }

                @Get("/multiple")
                public String fallback5() {
                    return "fallback";
                }

                @Get("/matches/or")
                @MatchesHeader("my-header=2 || my-other-header")
                public String matchesOneOrAnother() {
                    return "my-header=2 || my-other-header";
                }

                @Get("/matches/or")
                public String fallback6() {
                    return "fallback";
                }

                @Get("/matches/multiple")
                @MatchesHeader("my-header=2 || my-other-header")
                @MatchesHeader("other-header")
                public String matchesMultiple() {
                    return "(my-header=2 || my-other-header) && other-header";
                }

                @Get("/matches/multiple")
                public String fallback7() {
                    return "fallback";
                }
            });
        }
    };

    private static WebClient client;

    @BeforeAll
    public static void beforeAll() {
        client = WebClient.of(extension.httpUri());
    }

    @Test
    void matches() {
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/matches", "my-header", "my-value")))
                         .aggregate().join().contentUtf8()).isEqualTo("my-header=my-value");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/matches", "my-header", "your-value")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void doesNotMatch() {
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/doesNotMatch", "my-header", "your-value")))
                         .aggregate().join().contentUtf8()).isEqualTo("my-header!=my-value");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/doesNotMatch", "my-header", "my-value")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void contains() {
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/contains", "my-header", "any-value")))
                         .aggregate().join().contentUtf8()).isEqualTo("my-header");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/contains", "your-header", "your-value")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void doesNotContain() {
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/doesNotContain", "your-header", "your-value")))
                         .aggregate().join().contentUtf8()).isEqualTo("!my-header");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/doesNotContain", "my-header", "my-value")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void multiple() {
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/multiple", "my-header", "my-value")))
                         .aggregate().join().contentUtf8()).isEqualTo("multiple");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/multiple", "my-header", "my-value", "your-header", "your-value")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void or() {
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                                 HttpMethod.GET, "/matches/or", "my-header", "2")))
                         .aggregate().join().contentUtf8()).isEqualTo("my-header=2 || my-other-header");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                                 HttpMethod.GET, "/matches/or", "my-other-header", "3")))
                         .aggregate().join().contentUtf8()).isEqualTo("my-header=2 || my-other-header");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                                 HttpMethod.GET, "/matches/or", "other-header", "3")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void matchesMultiple() {
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                                 HttpMethod.GET, "/matches/multiple", "my-header", "2", "other-header", "3")))
                         .aggregate().join().contentUtf8())
                .isEqualTo("(my-header=2 || my-other-header) && other-header");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                                 HttpMethod.GET, "/matches/multiple", "my-other-header", "3", "other-header",
                                 "3")))
                         .aggregate().join().contentUtf8())
                .isEqualTo("(my-header=2 || my-other-header) && other-header");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                                 HttpMethod.GET, "/matches/multiple", "other-header", "3")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                                 HttpMethod.GET, "/matches/multiple", "my-header", "2")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                                 HttpMethod.GET, "/matches/multiple", "my-other-header", "3")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }
}
