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
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class HttpServerParamPredicatesMatchingTest {

    @RegisterExtension
    public static final ServerExtension extension = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.route().get("/matches").matchesParams("my-param=my-value")
              .build((ctx, req) -> HttpResponse.of("my-param=my-value"))
              .route().get("/matches")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/doesNotMatch").matchesParams("my-param!=my-value")
              .build((ctx, req) -> HttpResponse.of("my-param!=my-value"))
              .route().get("/doesNotMatch")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/contains").matchesParams("my-param")
              .build((ctx, req) -> HttpResponse.of("my-param"))
              .route().get("/contains")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/doesNotContain").matchesParams("!my-param")
              .build((ctx, req) -> HttpResponse.of("!my-param"))
              .route().get("/doesNotContain")
              .build((ctx, req) -> HttpResponse.of("fallback"))
              .route().get("/matches/percentEncoded").matchesParams("my-param=/")
              .build((ctx, req) -> HttpResponse.of("my-param=/"))
              .route().get("/matches/percentEncoded").matchesParams("my-param=%2F")
              .build((ctx, req) -> HttpResponse.of("my-param=%2F"))
              .route().get("/custom").matchesParams("my-param", value -> Integer.parseInt(value) > 100)
              .build((ctx, req) -> HttpResponse.of("custom"))
              .route().get("/custom")
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
    void custom() {
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/custom?my-param=0101"))
                         .aggregate().join().contentUtf8()).isEqualTo("custom");
        assertThat(client.execute(HttpRequest.of(HttpMethod.GET, "/custom?my-param=2"))
                         .aggregate().join().contentUtf8()).isEqualTo("fallback");
    }
}
