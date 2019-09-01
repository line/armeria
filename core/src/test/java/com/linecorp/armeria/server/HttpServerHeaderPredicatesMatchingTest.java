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

import com.linecorp.armeria.client.HttpClient;
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
            sb.route().get("/matches").matchesHeaderPredicates("my-header=my-value")
              .build((ctx, req) -> HttpResponse.of("my-header=my-value"))
              .route().get("/matches")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/doesNotMatch").matchesHeaderPredicates("my-header!=my-value")
              .build((ctx, req) -> HttpResponse.of("my-header!=my-value"))
              .route().get("/doesNotMatch")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/contains").matchesHeaderPredicates("my-header")
              .build((ctx, req) -> HttpResponse.of("my-header"))
              .route().get("/contains")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/doesNotContain").matchesHeaderPredicates("!my-header")
              .build((ctx, req) -> HttpResponse.of("!my-header"))
              .route().get("/doesNotContain")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/multiple")
              .matchesHeaderPredicates("my-header=my-value")
              .matchesHeaderPredicates("!your-header")
              .build((ctx, req) -> HttpResponse.of("multiple"))
              .route().get("/multiple")
              .build((ctx, req) -> HttpResponse.of("fallback"));
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
