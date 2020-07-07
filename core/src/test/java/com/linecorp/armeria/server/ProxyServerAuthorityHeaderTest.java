/*
 * Copyright 2020 LINE Corporation
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

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ProxyServerAuthorityHeaderTest {

    private static final AtomicReference<RequestHeaders> backendHeaders = new AtomicReference<>();

    private static final AtomicReference<RequestHeaders> proxyHeaders = new AtomicReference<>();

    @RegisterExtension
    static final ServerExtension backend = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/toHttp1", (ctx, req) -> {
                backendHeaders.set(req.headers());
                return HttpResponse.of(200);
            });
            sb.service("/toHttp2", (ctx, req) -> {
                backendHeaders.set(req.headers());
                return HttpResponse.of(200);
            });
        }
    };

    @RegisterExtension
    static final ServerExtension proxy = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/toHttp1", (ctx, req) -> {
                proxyHeaders.set(req.headers());
                final WebClient client = WebClient.of(backend.uri(SessionProtocol.H1C));
                return client.execute(req);
            });
            sb.service("/toHttp2", (ctx, req) -> {
                proxyHeaders.set(req.headers());
                final WebClient client = WebClient.of(backend.uri(SessionProtocol.H2C));
                return client.execute(req);
            });
        }
    };

    @BeforeEach
    void setUp() {
        backendHeaders.set(null);
        proxyHeaders.set(null);
    }

    @Test
    void http1ToHttp1() {
        final WebClient client = WebClient.of(proxy.uri(SessionProtocol.H1C));
        client.get("/toHttp1").aggregate().join();
        final RequestHeaders proxyRequestHeaders = proxyHeaders.get();
        assertThat(proxyRequestHeaders.get(HttpHeaderNames.HOST)).isNotNull();
        assertThat(proxyRequestHeaders.get(HttpHeaderNames.AUTHORITY)).isNull();

        final RequestHeaders backendRequestHeaders = backendHeaders.get();
        assertThat(backendRequestHeaders.get(HttpHeaderNames.HOST)).isNotNull();
        assertThat(backendRequestHeaders.get(HttpHeaderNames.AUTHORITY)).isNull();
    }

    @Test
    void http2ToHttp2() {
        final WebClient client = WebClient.of(proxy.uri(SessionProtocol.H2C));
        client.get("/toHttp2").aggregate().join();
        final RequestHeaders proxyRequestHeaders = proxyHeaders.get();
        assertThat(proxyRequestHeaders.get(HttpHeaderNames.HOST)).isNull();
        assertThat(proxyRequestHeaders.get(HttpHeaderNames.AUTHORITY)).isNotNull();

        final RequestHeaders backendRequestHeaders = backendHeaders.get();
        assertThat(backendRequestHeaders.get(HttpHeaderNames.HOST)).isNull();
        assertThat(backendRequestHeaders.get(HttpHeaderNames.AUTHORITY)).isNotNull();
    }

    @Test
    void http1ToHttp2() {
        final WebClient client = WebClient.of(proxy.uri(SessionProtocol.H1C));
        client.get("/toHttp2").aggregate().join();
        final RequestHeaders proxyRequestHeaders = proxyHeaders.get();
        assertThat(proxyRequestHeaders.get(HttpHeaderNames.HOST)).isNotNull();
        assertThat(proxyRequestHeaders.get(HttpHeaderNames.AUTHORITY)).isNull();

        final RequestHeaders backendRequestHeaders = backendHeaders.get();
        assertThat(backendRequestHeaders.get(HttpHeaderNames.HOST)).isNotNull();
        assertThat(backendRequestHeaders.get(HttpHeaderNames.AUTHORITY)).isNull();
    }

    @Test
    void http2ToHttp1() {
        final WebClient client = WebClient.of(proxy.uri(SessionProtocol.H2C));
        client.get("/toHttp1").aggregate().join();
        final RequestHeaders proxyRequestHeaders = proxyHeaders.get();
        assertThat(proxyRequestHeaders.get(HttpHeaderNames.HOST)).isNull();
        assertThat(proxyRequestHeaders.get(HttpHeaderNames.AUTHORITY)).isNotNull();

        final RequestHeaders backendRequestHeaders = backendHeaders.get();
        assertThat(backendRequestHeaders.get(HttpHeaderNames.HOST)).isNotNull();
        assertThat(backendRequestHeaders.get(HttpHeaderNames.AUTHORITY)).isNull();
    }
}
