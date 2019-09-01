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

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class ConditionalHeaderTest {

    @RegisterExtension
    public static final ServerExtension extension = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new Object() {
                @Get("/matches")
                @ConditionalHeader("my-header=my-value")
                public String matches() {
                    return "my-header=my-value";
                }

                @Get("/matches")
                public String fallback1() {
                    return "fallback";
                }

                @Get("/doesNotMatch")
                @ConditionalHeader("my-header!=my-value")
                public String doesNotMatch() {
                    return "my-header!=my-value";
                }

                @Get("/doesNotMatch")
                public String fallback2() {
                    return "fallback";
                }

                @Get("/contains")
                @ConditionalHeader("my-header")
                public String contains() {
                    return "my-header";
                }

                @Get("/contains")
                public String fallback3() {
                    return "fallback";
                }

                @Get("/doesNotContain")
                @ConditionalHeader("!my-header")
                public String doesNotContain() {
                    return "!my-header";
                }

                @Get("/doesNotContain")
                public String fallback4() {
                    return "fallback";
                }

                @Get("/multiple")
                @ConditionalHeader("my-heaver=my-value")
                @ConditionalHeader("!your-header")
                public String multiple() {
                    return "multiple";
                }

                @Get("/multiple")
                public String fallback5() {
                    return "fallback";
                }
            });
        }
    };

    private static HttpClient client;

    @BeforeAll
    public static void beforeAll() {
        client = HttpClient.of(extension.uri("/"));
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
}
