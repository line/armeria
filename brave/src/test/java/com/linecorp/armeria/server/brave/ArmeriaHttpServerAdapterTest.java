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

class ArmeriaHttpServerAdapterTest {

    @Mock
    private RequestLog requestLog;

    @Test
    public void path() {
        when(requestLog.path()).thenReturn("/foo");
        assertThat(ArmeriaHttpServerAdapter.get().path(requestLog)).isEqualTo("/foo");
    }

    @Test
    public void method() {
        when(requestLog.method()).thenReturn(HttpMethod.GET);
        assertThat(ArmeriaHttpServerAdapter.get().method(requestLog)).isEqualTo("GET");
    }

    @Test
    public void url() {
        when(requestLog.isAvailable(RequestLogAvailability.SCHEME)).thenReturn(true);
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)).thenReturn(true);
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        when(requestLog.authority()).thenReturn("example.com");
        when(requestLog.path()).thenReturn("/foo");
        when(requestLog.query()).thenReturn("name=hoge");
        assertThat(ArmeriaHttpServerAdapter.get().url(requestLog)).isEqualTo(
                "http://example.com/foo?name=hoge");
    }

    @Test
    public void statusCode() {
        when(requestLog.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)).thenReturn(true);
        when(requestLog.status()).thenReturn(HttpStatus.OK);
        assertThat(ArmeriaHttpServerAdapter.get().statusCode(requestLog)).isEqualTo(200);
        assertThat(ArmeriaHttpServerAdapter.get().statusCodeAsInt(requestLog)).isEqualTo(200);

        when(requestLog.status()).thenReturn(HttpStatus.UNKNOWN);
        assertThat(ArmeriaHttpServerAdapter.get().statusCode(requestLog)).isNull();
        assertThat(ArmeriaHttpServerAdapter.get().statusCodeAsInt(requestLog)).isEqualTo(0);
    }

    @Test
    public void statusCode_notAvailable() {
        when(requestLog.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)).thenReturn(false);
        assertThat(ArmeriaHttpServerAdapter.get().statusCode(requestLog)).isNull();
        assertThat(ArmeriaHttpServerAdapter.get().statusCodeAsInt(requestLog)).isEqualTo(0);
    }

    @Test
    public void authority() {
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)).thenReturn(true);
        when(requestLog.authority()).thenReturn("example.com");
        assertThat(ArmeriaHttpServerAdapter.get().authority(requestLog)).isEqualTo("example.com");
    }

    @Test
    public void protocol() {
        when(requestLog.isAvailable(RequestLogAvailability.SCHEME)).thenReturn(true);
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        assertThat(ArmeriaHttpServerAdapter.get().protocol(requestLog)).isEqualTo("http");
    }

    @Test
    public void serializationFormat() {
        when(requestLog.isAvailable(RequestLogAvailability.SCHEME)).thenReturn(true);
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.of("tjson"), SessionProtocol.HTTP));
        assertThat(ArmeriaHttpServerAdapter.get().serializationFormat(requestLog)).isEqualTo("tjson");

        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        assertThat(ArmeriaHttpServerAdapter.get().serializationFormat(requestLog)).isNull();
    }

    @Test
    public void rpcMethod() {
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_CONTENT)).thenReturn(true);
        assertThat(ArmeriaHttpServerAdapter.get().rpcMethod(requestLog)).isNull();

        final RpcRequest rpcRequest = mock(RpcRequest.class);
        when(requestLog.requestContent()).thenReturn(rpcRequest);
        when(rpcRequest.method()).thenReturn("foo");
        assertThat(ArmeriaHttpServerAdapter.get().rpcMethod(requestLog)).isEqualTo("foo");
    }

    @Test
    public void requestHeader() {
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)).thenReturn(true);
        final RequestHeaders requestHeaders = mock(RequestHeaders.class);
        when(requestLog.requestHeaders()).thenReturn(requestHeaders);
        when(requestHeaders.get("foo")).thenReturn("bar");
        assertThat(ArmeriaHttpServerAdapter.get().requestHeader(requestLog, "foo")).isEqualTo("bar");
    }

    @Test
    public void parseClientIpAndPort() throws Exception {
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
        assertThat(ArmeriaHttpServerAdapter.get().parseClientIpAndPort(requestLog, span)).isTrue();
    }

    @Test
    public void route() {
        final ServiceRequestContext context = mock(ServiceRequestContext.class);
        when(requestLog.context()).thenReturn(context);
        when(context.route()).thenReturn(Route.builder().path("/foo/:bar/hoge").build());
        assertThat(ArmeriaHttpServerAdapter.get().route(requestLog)).isEqualTo("/foo/:/hoge");
    }

    @Test
    public void route_prefix() {
        final ServiceRequestContext context = mock(ServiceRequestContext.class);
        when(requestLog.context()).thenReturn(context);
        when(context.route()).thenReturn(Route.builder().path("exact:/foo").build());
        assertThat(ArmeriaHttpServerAdapter.get().route(requestLog)).isEqualTo("/foo");
    }

    @Test
    public void route_pathWithPrefix() {
        final ServiceRequestContext context = mock(ServiceRequestContext.class);
        when(requestLog.context()).thenReturn(context);
        when(context.route()).thenReturn(Route.builder().pathWithPrefix("/foo/", "glob:bar").build());
        assertThat(ArmeriaHttpServerAdapter.get().route(requestLog)).isEqualTo("/foo/ ^/(?:.+/)?bar$");
    }
}
