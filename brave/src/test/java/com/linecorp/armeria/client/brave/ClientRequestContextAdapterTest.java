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
 *
 */

package com.linecorp.armeria.client.brave;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;

import brave.http.HttpClientRequest;
import brave.http.HttpClientResponse;

class ClientRequestContextAdapterTest {
    @Test
    void path() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/foo");
        final HttpClientRequest braveReq = ClientRequestContextAdapter.asHttpClientRequest(
                ClientRequestContext.of(req),
                req.headers().toBuilder());

        assertThat(braveReq.path()).isEqualTo("/foo");
    }

    @Test
    void method() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/foo");
        final HttpClientRequest braveReq = ClientRequestContextAdapter.asHttpClientRequest(
                ClientRequestContext.of(req),
                req.headers().toBuilder());

        assertThat(braveReq.method()).isEqualTo("GET");
    }

    @Test
    void url() {
        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/foo?name=hoge",
                                  HttpHeaderNames.SCHEME, "http",
                                  HttpHeaderNames.AUTHORITY, "example.com"));

        final HttpClientRequest braveReq = ClientRequestContextAdapter.asHttpClientRequest(
                ClientRequestContext.of(req),
                req.headers().toBuilder());

        assertThat(braveReq.url()).isEqualTo("http://example.com/foo?name=hoge");
    }

    @Test
    void statusCode() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);
        ctx.logBuilder().endRequest();
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        ctx.logBuilder().endResponse();

        final HttpClientResponse res =
                ClientRequestContextAdapter.asHttpClientResponse(ctx.log().ensureComplete(), null);

        assertThat(res.statusCode()).isEqualTo(200);
    }

    @Test
    void protocol() {
        final ClientRequestContext ctx = ClientRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                                             .sessionProtocol(SessionProtocol.H2C)
                                                             .build();
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();
        assertThat(ClientRequestContextAdapter.protocol(ctx.log().ensureComplete())).isEqualTo("h2c");
    }

    @Test
    void serializationFormat() {
        final ClientRequestContext ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().serializationFormat(SerializationFormat.UNKNOWN);
        ctx1.logBuilder().endRequest();
        ctx1.logBuilder().endResponse();

        assertThat(ClientRequestContextAdapter.serializationFormat(ctx1.log().ensureComplete()))
                .isEqualTo("unknown");

        final ClientRequestContext ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().endRequest();
        ctx2.logBuilder().endResponse();
        assertThat(ClientRequestContextAdapter.serializationFormat(ctx2.log().ensureComplete())).isNull();
    }

    @Test
    void requestHeader() {
        final ClientRequestContext ctx = ClientRequestContext.of(
                        HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/", "foo", "bar")));
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();

        final HttpClientRequest braveReq =
                ClientRequestContextAdapter.asHttpClientRequest(ctx,
                                                                ctx.request().headers().toBuilder());
        assertThat(braveReq.header("foo")).isEqualTo("bar");
        assertThat(braveReq.header("bar")).isNull();
    }
}
