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
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MatchesParamTest {

    @RegisterExtension
    public static final ServerExtension extension = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {
                @Get("/matches")
                @MatchesParam("my-param=my-value")
                public String matches() {
                    return "my-param=my-value";
                }

                @Get("/matches")
                public String fallback1() {
                    return "fallback";
                }

                @Get("/doesNotMatch")
                @MatchesParam("my-param!=my-value")
                public String doesNotMatch() {
                    return "my-param!=my-value";
                }

                @Get("/doesNotMatch")
                public String fallback2() {
                    return "fallback";
                }

                @Get("/contains")
                @MatchesParam("my-param")
                public String contains() {
                    return "my-param";
                }

                @Get("/contains")
                public String fallback3() {
                    return "fallback";
                }

                @Get("/doesNotContain")
                @MatchesParam("!my-param")
                public String doesNotContain() {
                    return "!my-param";
                }

                @Get("/doesNotContain")
                public String fallback4() {
                    return "fallback";
                }

                @Get("/matches/percentEncoded")
                @MatchesParam("my-param=/")
                public String matchesPercentEncoded1() {
                    return "my-param=/";
                }

                @Get("/matches/percentEncoded")
                @MatchesParam("my-param=%2F")
                public String matchesPercentEncoded2() {
                    return "my-param=%2F";
                }

                @Get("/matches/or")
                @MatchesParam("my-param=2 || my-other-param")
                public String matchesOneOrAnother() {
                    return "my-param=2 || my-other-param";
                }

                @Get("/matches/or")
                public String fallback5() {
                    return "fallback";
                }

                @Get("/matches/orMultipleConditions")
                @MatchesParam(" !my-other-param || my-param= 2 || my-other-other-param || my-other-param=3")
                public String matchesOneOrAnother2() {
                    return "!my-other-param || my-param= 2 || my-other-other-param || my-other-param=3";
                }

                @Get("/matches/orMultipleConditions")
                public String fallback6() {
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
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches?my-param=my-value"))
                         .aggregate().join().contentUtf8()).isEqualTo("my-param=my-value");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches?my-param=your-value"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void doesNotMatch() {
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/doesNotMatch?my-param=your-value"))
                         .aggregate().join().contentUtf8()).isEqualTo("my-param!=my-value");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/doesNotMatch?my-param=my-value"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void contains() {
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/contains?my-param=any-value"))
                         .aggregate().join().contentUtf8()).isEqualTo("my-param");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/contains?your-param=your-value"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void doesNotContain() {
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/doesNotContain?your-param=your-value"))
                         .aggregate().join().contentUtf8()).isEqualTo("!my-param");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/doesNotContain?my-param=my-value"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void percentEncoded() {
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches/percentEncoded?my-param=/"))
                         .aggregate().join().contentUtf8()).isEqualTo("my-param=/");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches/percentEncoded?my-param=%2F"))
                         .aggregate().join().contentUtf8()).isEqualTo("my-param=/");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches/percentEncoded?my-param=%252F"))
                         .aggregate().join().contentUtf8()).isEqualTo("my-param=%2F");
    }

    @Test
    void or() {
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches/or?my-param=2"))
                         .aggregate().join().contentUtf8()).isEqualTo("my-param=2 || my-other-param");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches/or?my-other-param"))
                         .aggregate().join().contentUtf8()).isEqualTo("my-param=2 || my-other-param");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches/or?my-param=3"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches/or?!my-other-param"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches/or"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches/or?my-param"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void orMultipleConditions() {
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/matches/orMultipleConditions"))
                         .aggregate().join().contentUtf8())
                .isEqualTo("!my-other-param || my-param= 2 || my-other-other-param || my-other-param=3");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET,
                                                 "/matches/orMultipleConditions?my-other-param&my-param= 2"))
                         .aggregate().join().contentUtf8())
                .isEqualTo("!my-other-param || my-param= 2 || my-other-other-param || my-other-param=3");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET,
                                                 "/matches/orMultipleConditions?my-other-param" +
                                                         "&my-other-other-param"))
                         .aggregate().join().contentUtf8())
                .isEqualTo("!my-other-param || my-param= 2 || my-other-other-param || my-other-param=3");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET,
                                                 "/matches/orMultipleConditions?my-other-param=3"))
                         .aggregate().join().contentUtf8())
                .isEqualTo("!my-other-param || my-param= 2 || my-other-other-param || my-other-param=3");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET,
                                                 "/matches/orMultipleConditions?my-other-param"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET,
                                                 "/matches/orMultipleConditions?my-other-param&my-param=10"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET,
                                                 "/matches/orMultipleConditions?my-other-param=20"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }
}
