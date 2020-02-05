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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpServerHeaderPredicatesMatchingTest {

    @RegisterExtension
    public static final ServerExtension extension = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.route().get("/matches").matchesHeaders("my-header=my-value")
              .build((ctx, req) -> HttpResponse.of("my-header=my-value"))
              .route().get("/matches")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/doesNotMatch").matchesHeaders("my-header!=my-value")
              .build((ctx, req) -> HttpResponse.of("my-header!=my-value"))
              .route().get("/doesNotMatch")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/contains").matchesHeaders("my-header")
              .build((ctx, req) -> HttpResponse.of("my-header"))
              .route().get("/contains")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/doesNotContain").matchesHeaders("!my-header")
              .build((ctx, req) -> HttpResponse.of("!my-header"))
              .route().get("/doesNotContain")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/multiple")
              .matchesHeaders("my-header=my-value")
              .matchesHeaders("!your-header")
              .build((ctx, req) -> HttpResponse.of("multiple"))
              .route().get("/multiple")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/custom").matchesHeaders("my-header", value -> Integer.parseInt(value) > 100)
              .build((ctx, req) -> HttpResponse.of("custom"))
              .route().get("/custom")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/custom/composed")
              .matchesHeaders("my-header", value -> Integer.parseInt(value) > 100)
              .matchesHeaders("your-header", "ok"::equals)
              .build((ctx, req) -> HttpResponse.of("custom"))
              .route().get("/custom/composed")
              .build((ctx, req) -> HttpResponse.of("fallback"));
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
    void custom() {
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/custom", "my-header", "0101")))
                         .aggregate().join().contentUtf8()).isEqualTo("custom");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/custom", "my-header", "2")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }

    @Test
    void customComposed() {
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/custom/composed", "my-header", "0101", "your-header", "ok")))
                         .aggregate().join().contentUtf8()).isEqualTo("custom");

        // Partial matching won't be accepted.
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/custom/composed", "your-header", "0101")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
        assertThat(client.execute(HttpRequest.of(RequestHeaders.of(
                HttpMethod.GET, "/custom/composed", "your-header", "nok")))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }
}
