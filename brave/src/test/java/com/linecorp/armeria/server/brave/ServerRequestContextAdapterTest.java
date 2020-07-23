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

package com.linecorp.armeria.server.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.Span;
import brave.http.HttpServerRequest;
import brave.http.HttpServerResponse;

class ServerRequestContextAdapterTest {
    @Test
    void path() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/foo");
        final HttpServerRequest braveReq = ServiceRequestContextAdapter.asHttpServerRequest(
                ServiceRequestContext.of(req));

        assertThat(braveReq.path()).isEqualTo("/foo");
    }

    @Test
    void method() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/foo");
        final HttpServerRequest braveReq = ServiceRequestContextAdapter.asHttpServerRequest(
                ServiceRequestContext.of(req));

        assertThat(braveReq.method()).isEqualTo("GET");
    }

    @Test
    void url() {
        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/foo?name=hoge",
                                  HttpHeaderNames.SCHEME, "http",
                                  HttpHeaderNames.AUTHORITY, "example.com"));

        final HttpServerRequest braveReq = ServiceRequestContextAdapter.asHttpServerRequest(
                ServiceRequestContext.of(req));

        assertThat(braveReq.url()).isEqualTo("http://example.com/foo?name=hoge");
    }

    @Test
    void statusCode() {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        ctx.logBuilder().endRequest();
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        ctx.logBuilder().endResponse();

        final HttpServerResponse res =
                ServiceRequestContextAdapter.asHttpServerResponse(ctx.log().ensureComplete(), null);

        assertThat(res.statusCode()).isEqualTo(200);
    }

    @Test
    void serializationFormat() {
        final ServiceRequestContext ctx1 = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx1.logBuilder().serializationFormat(SerializationFormat.UNKNOWN);
        ctx1.logBuilder().endRequest();
        ctx1.logBuilder().endResponse();

        assertThat(ServiceRequestContextAdapter.serializationFormat(ctx1.log().ensureComplete()))
                .isEqualTo("unknown");

        final ServiceRequestContext ctx2 = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2.logBuilder().endRequest();
        ctx2.logBuilder().endResponse();
        assertThat(ServiceRequestContextAdapter.serializationFormat(ctx2.log().ensureComplete())).isNull();
    }

    @Test
    void requestHeader() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/", "foo", "bar")));
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();

        final HttpServerRequest braveReq =
                ServiceRequestContextAdapter.asHttpServerRequest(ctx);
        assertThat(braveReq.header("foo")).isEqualTo("bar");
        assertThat(braveReq.header("bar")).isNull();
    }

    @Test
    void parseClientIpAndPort() throws Exception {
        final ServiceRequestContext ctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                     .remoteAddress(new InetSocketAddress("127.0.0.1", 1234))
                                     .build();

        final HttpServerRequest req =
                ServiceRequestContextAdapter.asHttpServerRequest(ctx);
        final Span span = mock(Span.class);
        when(span.remoteIpAndPort("127.0.0.1", 1234)).thenReturn(true);
        assertThat(req.parseClientIpAndPort(span)).isTrue();
        verify(span, times(1)).remoteIpAndPort("127.0.0.1", 1234);
        verifyNoMoreInteractions(span);
    }

    @Test
    void route() {
        final HttpServerRequest res1 = newRouteRequest(Route.builder()
                                                           .path("/foo/:bar/hoge")
                                                           .build());
        assertThat(res1.route()).isEqualTo("/foo/:bar/hoge");

        final HttpServerRequest res2 = newRouteRequest(Route.builder()
                                                           .path("/foo/{bar}/hoge")
                                                           .build());
        assertThat(res2.route()).isEqualTo("/foo/:bar/hoge");
    }

    @Test
    void route_prefix() {
        final HttpServerRequest res = newRouteRequest(Route.builder()
                                                           .path("exact:/foo")
                                                           .build());
        assertThat(res.route()).isEqualTo("/foo");
    }

    @Test
    void route_pathWithPrefix_glob() {
        final HttpServerRequest res = newRouteRequest(Route.builder()
                                                           .path("/foo/", "glob:bar")
                                                           .build());
        assertThat(res.route()).isEqualTo("/foo/**/bar");
    }

    @Test
    void route_pathWithPrefix_regex() {
        final HttpServerRequest res = newRouteRequest(Route.builder()
                                                           .path("/foo/", "regex:(bar|baz)")
                                                           .build());
        assertThat(res.route()).isEqualTo("/foo/(bar|baz)");
    }

    private static HttpServerRequest newRouteRequest(Route route) {
        final ServiceRequestContext ctx =
                ServiceRequestContext.builder(HttpRequest.of(HttpMethod.GET, "/"))
                                     .route(route)
                                     .build();

        return ServiceRequestContextAdapter.asHttpServerRequest(ctx);
    }
}
