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
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.Span;
import brave.http.HttpServerRequest;
import brave.http.HttpServerResponse;

class ServerRequestContextAdapterTest {

    @Mock
    private ServiceRequestContext ctx;
    @Mock
    private RequestLog requestLog;
    private HttpServerRequest request;
    private HttpServerResponse response;

    @BeforeEach
    void setup() {
        request = ServiceRequestContextAdapter.asHttpServerRequest(ctx);
        response = ServiceRequestContextAdapter.asHttpServerResponse(ctx);
    }

    @Test
    void path() {
        when(ctx.path()).thenReturn("/foo");
        assertThat(request.path()).isEqualTo("/foo");
    }

    @Test
    void method() {
        when(ctx.method()).thenReturn(HttpMethod.GET);
        assertThat(request.method()).isEqualTo("GET");
    }

    @Test
    void url() {
        when(ctx.request()).thenReturn(HttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/foo?name=hoge",
                                  HttpHeaderNames.SCHEME, "http",
                                  HttpHeaderNames.AUTHORITY, "example.com")));
        assertThat(request.url()).isEqualTo("http://example.com/foo?name=hoge");
    }

    @Test
    void statusCode() {
        when(ctx.log()).thenReturn(requestLog);
        when(requestLog.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)).thenReturn(true);
        when(requestLog.status()).thenReturn(HttpStatus.OK);
        assertThat(response.statusCode()).isEqualTo(200);

        when(requestLog.status()).thenReturn(HttpStatus.UNKNOWN);
        assertThat(response.statusCode()).isEqualTo(0);
    }

    @Test
    void statusCode_notAvailable() {
        when(ctx.log()).thenReturn(requestLog);
        when(requestLog.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)).thenReturn(false);
        assertThat(response.statusCode()).isEqualTo(0);
    }

    @Test
    void serializationFormat() {
        when(requestLog.isAvailable(RequestLogAvailability.SCHEME)).thenReturn(true);
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.of("tjson"), SessionProtocol.HTTP));
        assertThat(ServiceRequestContextAdapter.serializationFormat(requestLog)).isEqualTo("tjson");

        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        assertThat(ServiceRequestContextAdapter.serializationFormat(requestLog)).isNull();
    }

    @Test
    void rpcMethod() {
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_CONTENT)).thenReturn(true);
        assertThat(ServiceRequestContextAdapter.rpcMethod(requestLog)).isNull();

        final RpcRequest rpcRequest = mock(RpcRequest.class);
        when(requestLog.requestContent()).thenReturn(rpcRequest);
        when(rpcRequest.method()).thenReturn("foo");
        assertThat(ServiceRequestContextAdapter.rpcMethod(requestLog)).isEqualTo("foo");
    }

    @Test
    void requestHeader() {
        when(ctx.log()).thenReturn(requestLog);
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)).thenReturn(true);
        final RequestHeaders requestHeaders = mock(RequestHeaders.class);
        when(requestLog.requestHeaders()).thenReturn(requestHeaders);
        when(requestHeaders.get("foo")).thenReturn("bar");
        assertThat(request.header("foo")).isEqualTo("bar");
    }

    @Test
    void parseClientIpAndPort() throws Exception {
        when(ctx.remoteAddress())
                .thenReturn(new InetSocketAddress(
                        InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 1234));
        final Span span = mock(Span.class);
        when(span.remoteIpAndPort("127.0.0.1", 1234)).thenReturn(true);
        assertThat(request.parseClientIpAndPort(span)).isTrue();
    }

    @Test
    void route() {
        when(ctx.route()).thenReturn(Route.builder().path("/foo/:bar/hoge").build());
        assertThat(response.route()).isEqualTo("/foo/:/hoge");
    }

    @Test
    void route_prefix() {
        when(ctx.route()).thenReturn(Route.builder().path("exact:/foo").build());
        assertThat(response.route()).isEqualTo("/foo");
    }

    @Test
    void route_pathWithPrefix_glob() {
        when(ctx.route()).thenReturn(Route.builder().pathWithPrefix("/foo/", "glob:bar").build());
        assertThat(response.route()).isEqualTo("/foo/**/bar");
    }

    @Test
    void route_pathWithPrefix_regex() {
        when(ctx.route()).thenReturn(Route.builder().pathWithPrefix("/foo/", "regex:(bar|baz)").build());
        assertThat(response.route()).isEqualTo("/foo/(bar|baz)");
    }
}
