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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import com.linecorp.armeria.client.ClientRequestContext;
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

class ArmeriaHttpClientAdapterTest {

    @Mock
    private RequestLog requestLog;

    @Test
    void path() {
        when(requestLog.path()).thenReturn("/foo");
        assertThat(ArmeriaHttpClientAdapter.get().path(requestLog)).isEqualTo("/foo");
    }

    @Test
    void method() {
        when(requestLog.method()).thenReturn(HttpMethod.GET);
        assertThat(ArmeriaHttpClientAdapter.get().method(requestLog)).isEqualTo("GET");
    }

    @Test
    void url() {
        when(requestLog.context()).thenReturn(
                ClientRequestContext.of(HttpRequest.of(
                        RequestHeaders.of(HttpMethod.GET, "/foo?name=hoge",
                                          HttpHeaderNames.AUTHORITY, "example.com"))));
        assertThat(ArmeriaHttpClientAdapter.get().url(requestLog)).isEqualTo("http://example.com/foo?name=hoge");
    }

    @Test
    void statusCode() {
        when(requestLog.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)).thenReturn(true);
        when(requestLog.status()).thenReturn(HttpStatus.OK);
        assertThat(ArmeriaHttpClientAdapter.get().statusCode(requestLog)).isEqualTo(200);
        assertThat(ArmeriaHttpClientAdapter.get().statusCodeAsInt(requestLog)).isEqualTo(200);

        when(requestLog.status()).thenReturn(HttpStatus.UNKNOWN);
        assertThat(ArmeriaHttpClientAdapter.get().statusCode(requestLog)).isNull();
        assertThat(ArmeriaHttpClientAdapter.get().statusCodeAsInt(requestLog)).isEqualTo(0);
    }

    @Test
    void statusCode_notAvailable() {
        when(requestLog.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)).thenReturn(false);
        assertThat(ArmeriaHttpClientAdapter.get().statusCode(requestLog)).isNull();
        assertThat(ArmeriaHttpClientAdapter.get().statusCodeAsInt(requestLog)).isEqualTo(0);
    }

    @Test
    void authority() {
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)).thenReturn(true);
        when(requestLog.authority()).thenReturn("example.com");
        assertThat(ArmeriaHttpClientAdapter.get().authority(requestLog)).isEqualTo("example.com");
    }

    @Test
    void protocol() {
        when(requestLog.isAvailable(RequestLogAvailability.SCHEME)).thenReturn(true);
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        assertThat(ArmeriaHttpClientAdapter.get().protocol(requestLog)).isEqualTo("http");
    }

    @Test
    void serializationFormat() {
        when(requestLog.isAvailable(RequestLogAvailability.SCHEME)).thenReturn(true);
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.of("tjson"), SessionProtocol.HTTP));
        assertThat(ArmeriaHttpClientAdapter.get().serializationFormat(requestLog)).isEqualTo("tjson");
        when(requestLog.scheme()).thenReturn(Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP));
        assertThat(ArmeriaHttpClientAdapter.get().serializationFormat(requestLog)).isNull();
    }

    @Test
    void rpcMethod() {
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_CONTENT)).thenReturn(true);
        assertThat(ArmeriaHttpClientAdapter.get().rpcMethod(requestLog)).isNull();

        final RpcRequest rpcRequest = mock(RpcRequest.class);
        when(requestLog.requestContent()).thenReturn(rpcRequest);
        when(rpcRequest.method()).thenReturn("foo");
        assertThat(ArmeriaHttpClientAdapter.get().rpcMethod(requestLog)).isEqualTo("foo");
    }

    @Test
    void requestHeader() {
        when(requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)).thenReturn(true);
        final RequestHeaders requestHeaders = mock(RequestHeaders.class);
        when(requestLog.requestHeaders()).thenReturn(requestHeaders);
        when(requestHeaders.get("foo")).thenReturn("bar");
        assertThat(ArmeriaHttpClientAdapter.get().requestHeader(requestLog, "foo")).isEqualTo("bar");
    }
}
