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

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
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

class RequestLogAdapterTest {

    @Mock
    private RequestLog requestLog;
    private HttpServerRequest request;
    private HttpServerResponse response;

    @BeforeEach
    void setup() {
        request = RequestLogAdapter.asHttpServerRequest(requestLog);
        response = RequestLogAdapter.asHttpServerResponse(requestLog);
    }

    @Test
    void path() {
        when(requestLog.path()).thenReturn("/foo");
        assertThat(request.path()).isEqualTo("/foo");
    }

    @Test
    void method() {
        when(requestLog.method()).thenReturn(HttpMethod.GET);
        assertThat(request.method()).isEqualTo("GET");
    }

    @Test
    void url() {
        when(requestLog.isAvailable(RequestLogAvailability.SCHEME)).thenReturn(true);
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)).thenReturn(true);
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        when(requestLog.authority()).thenReturn("example.com");
        when(requestLog.path()).thenReturn("/foo");
        when(requestLog.query()).thenReturn("name=hoge");
        assertThat(request.url()).isEqualTo("http://example.com/foo?name=hoge");
    }

    @Test
    void statusCode() {
        when(requestLog.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)).thenReturn(true);
        when(requestLog.status()).thenReturn(HttpStatus.OK);
        assertThat(response.statusCode()).isEqualTo(200);

        when(requestLog.status()).thenReturn(HttpStatus.UNKNOWN);
        assertThat(response.statusCode()).isEqualTo(0);
    }

    @Test
    void statusCode_notAvailable() {
        when(requestLog.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)).thenReturn(false);
        assertThat(response.statusCode()).isEqualTo(0);
    }

    @Test
    void authority() {
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)).thenReturn(true);
        when(requestLog.authority()).thenReturn("example.com");
        assertThat(RequestLogAdapter.authority(requestLog)).isEqualTo("example.com");
    }

    @Test
    void protocol() {
        when(requestLog.isAvailable(RequestLogAvailability.SCHEME)).thenReturn(true);
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        assertThat(RequestLogAdapter.protocol(requestLog)).isEqualTo("http");
    }

    @Test
    void serializationFormat() {
        when(requestLog.isAvailable(RequestLogAvailability.SCHEME)).thenReturn(true);
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.of("tjson"), SessionProtocol.HTTP));
        assertThat(RequestLogAdapter.serializationFormat(requestLog)).isEqualTo("tjson");

        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        assertThat(RequestLogAdapter.serializationFormat(requestLog)).isNull();
    }

    @Test
    void rpcMethod() {
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_CONTENT)).thenReturn(true);
        assertThat(RequestLogAdapter.rpcMethod(requestLog)).isNull();

        final RpcRequest rpcRequest = mock(RpcRequest.class);
        when(requestLog.requestContent()).thenReturn(rpcRequest);
        when(rpcRequest.method()).thenReturn("foo");
        assertThat(RequestLogAdapter.rpcMethod(requestLog)).isEqualTo("foo");
    }

    @Test
    void requestHeader() {
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)).thenReturn(true);
        final RequestHeaders requestHeaders = mock(RequestHeaders.class);
        when(requestLog.requestHeaders()).thenReturn(requestHeaders);
        when(requestHeaders.get("foo")).thenReturn("bar");
        assertThat(request.header("foo")).isEqualTo("bar");
    }

    @Test
    void parseClientIpAndPort() throws Exception {
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)).thenReturn(true);
        final RequestHeaders requestHeaders = mock(RequestHeaders.class);
        when(requestLog.requestHeaders()).thenReturn(requestHeaders);

        final RequestContext requestContext = mock(RequestContext.class);
        when(requestLog.context()).thenReturn(requestContext);
        when(requestContext.remoteAddress())
                .thenReturn(new InetSocketAddress(
                        InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 1234));
        final Span span = mock(Span.class);
        when(span.remoteIpAndPort("127.0.0.1", 1234)).thenReturn(true);
        assertThat(request.parseClientIpAndPort(span)).isTrue();
    }

    @Test
    void route() {
        final ServiceRequestContext context = mock(ServiceRequestContext.class);
        when(requestLog.context()).thenReturn(context);
        when(context.route()).thenReturn(Route.builder().path("/foo/:bar/hoge").build());
        assertThat(response.route()).isEqualTo("/foo/:/hoge");
    }

    @Test
    void route_prefix() {
        final ServiceRequestContext context = mock(ServiceRequestContext.class);
        when(requestLog.context()).thenReturn(context);
        when(context.route()).thenReturn(Route.builder().path("exact:/foo").build());
        assertThat(response.route()).isEqualTo("/foo");
    }

    @Test
    void route_pathWithPrefix_glob() {
        final ServiceRequestContext context = mock(ServiceRequestContext.class);
        when(requestLog.context()).thenReturn(context);
        when(context.route()).thenReturn(Route.builder().pathWithPrefix("/foo/", "glob:bar").build());
        assertThat(response.route()).isEqualTo("/foo/**/bar");
    }

    @Test
    void route_pathWithPrefix_regex() {
        final ServiceRequestContext context = mock(ServiceRequestContext.class);
        when(requestLog.context()).thenReturn(context);
        when(context.route()).thenReturn(Route.builder().pathWithPrefix("/foo/", "regex:(bar|baz)").build());
        assertThat(response.route()).isEqualTo("/foo/(bar|baz)");
    }
}
