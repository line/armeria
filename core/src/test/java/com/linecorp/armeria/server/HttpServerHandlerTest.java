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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerHandlerTest {

    private static final AtomicReference<RequestLog> logHolder = new AtomicReference<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.accessLogWriter(logHolder::set, true);
            sb.route()
              .get("/hello")
              .build((ctx, req) -> HttpResponse.of(200));
            sb.route()
              .get("/httpStatusException")
              .build((ctx, req) -> {
                  throw HttpStatusException.of(201);
              });
            sb.route()
              .get("/httpResponseException")
              .build((ctx, req) -> {
                  throw HttpResponseException.of(HttpResponse.of(201));
              });
        }
    };

    @BeforeEach
    void clearLog() {
        logHolder.set(null);
    }

    @Test
    void methodNotAllowed() {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.delete("/hello").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
        await().untilAsserted(() -> {
            assertThat(logHolder.get().requestHeaders().path()).isEqualTo("/hello");
        });
        assertThat(logHolder.get().requestCause()).isNull();
    }

    @Test
    void handleNonExistentMapping() {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/non_existent").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.NOT_FOUND);
        await().untilAsserted(() -> {
            assertThat(logHolder.get().requestHeaders().path()).isEqualTo("/non_existent");
        });
        assertThat(logHolder.get().requestCause()).isNull();
    }

    @Test
    void httpStatusExceptionIsNotLoggedAsRequestCause() {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/httpStatusException").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.CREATED);
        await().untilAsserted(() -> {
            assertThat(logHolder.get().requestHeaders().path()).isEqualTo("/httpStatusException");
        });
        assertThat(logHolder.get().requestCause()).isNull();
    }

    @Test
    void httpResponseExceptionIsNotLoggedAsRequestCause() {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.get("/httpResponseException").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.CREATED);
        await().untilAsserted(() -> {
            assertThat(logHolder.get().requestHeaders().path()).isEqualTo("/httpResponseException");
        });
        assertThat(logHolder.get().requestCause()).isNull();
    }
}
