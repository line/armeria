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

import brave.Span;

class ArmeriaHttpServerAdapterTest {

    @Mock
    private RequestLog requestLog;

    private final ArmeriaHttpServerAdapter adapter = new ArmeriaHttpServerAdapter();

    @Test
    public void path() {
        when(requestLog.path()).thenReturn("/foo");
        assertThat(adapter.path(requestLog)).isEqualTo("/foo");
    }

    @Test
    public void method() {
        when(requestLog.method()).thenReturn(HttpMethod.GET);
        assertThat(adapter.method(requestLog)).isEqualTo("GET");
    }

    @Test
    public void url() {
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        when(requestLog.authority()).thenReturn("example.com");
        when(requestLog.path()).thenReturn("/foo");
        when(requestLog.query()).thenReturn("name=hoge");
        assertThat(adapter.url(requestLog)).isEqualTo("http://example.com/foo?name=hoge");
    }

    @Test
    public void statusCode() {
        when(requestLog.status()).thenReturn(HttpStatus.OK);
        assertThat(adapter.statusCode(requestLog)).isEqualTo(200);
        assertThat(adapter.statusCodeAsInt(requestLog)).isEqualTo(200);

        when(requestLog.status()).thenReturn(HttpStatus.UNKNOWN);
        assertThat(adapter.statusCode(requestLog)).isNull();
        assertThat(adapter.statusCodeAsInt(requestLog)).isEqualTo(0);
    }

    @Test
    public void authority() {
        when(requestLog.authority()).thenReturn("example.com");
        assertThat(adapter.authority(requestLog)).isEqualTo("example.com");
    }

    @Test
    public void protocol() {
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        assertThat(adapter.protocol(requestLog)).isEqualTo("http");
    }

    @Test
    public void serializationFormat() {
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.of("tjson"), SessionProtocol.HTTP));
        assertThat(adapter.serializationFormat(requestLog)).isEqualTo("tjson");

        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        assertThat(adapter.serializationFormat(requestLog)).isNull();
    }

    @Test
    public void rpcMethod() {
        assertThat(adapter.rpcMethod(requestLog)).isNull();

        final RpcRequest rpcRequest = mock(RpcRequest.class);
        when(requestLog.requestContent()).thenReturn(rpcRequest);
        when(rpcRequest.method()).thenReturn("foo");
        assertThat(adapter.rpcMethod(requestLog)).isEqualTo("foo");
    }

    @Test
    public void requestHeader() {
        final RequestHeaders requestHeaders = mock(RequestHeaders.class);
        when(requestLog.requestHeaders()).thenReturn(requestHeaders);
        when(requestHeaders.get("foo")).thenReturn("bar");
        assertThat(adapter.requestHeader(requestLog, "foo")).isEqualTo("bar");
    }

    @Test
    public void parseClientIpAndPort() throws Exception {
        final RequestHeaders requestHeaders = mock(RequestHeaders.class);
        when(requestLog.requestHeaders()).thenReturn(requestHeaders);

        final RequestContext requestContext = mock(RequestContext.class);
        when(requestLog.context()).thenReturn(requestContext);
        when(requestContext.remoteAddress())
                .thenReturn(new InetSocketAddress(
                        InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), 1234));
        final Span span = mock(Span.class);
        when(span.remoteIpAndPort("127.0.0.1", 1234)).thenReturn(true);
        assertThat(adapter.parseClientIpAndPort(requestLog, span)).isTrue();
    }
}
