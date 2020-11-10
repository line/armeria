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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpClientContextCaptorTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/foo", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void simple() {
        final WebClient client = WebClient.of(server.httpUri());
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            final HttpResponse res = client.get("/foo");
            final ClientRequestContext ctx = ctxCaptor.get();
            assertThat(ctx.path()).isEqualTo("/foo");
            res.aggregate();
        }
    }

    @Test
    void connectionRefused() {
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            final HttpResponse res = WebClient.of().get("http://127.0.0.1:1/foo");
            final ClientRequestContext ctx = ctxCaptor.get();
            assertThat(ctx.path()).isEqualTo("/foo");
            res.aggregate();
        }
    }

    @Test
    void badPath() {
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            // Send a request with a bad path.
            final HttpResponse res = WebClient.of().get("http://127.0.0.1:1/|");
            assertThatThrownBy(ctxCaptor::get).isInstanceOf(NoSuchElementException.class)
                                              .hasMessageContaining("no request was made");
            res.aggregate();
        }
    }
}
