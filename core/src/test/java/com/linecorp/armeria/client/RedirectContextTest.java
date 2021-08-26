/*
 * Copyright 2021 LINE Corporation
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.RedirectingClient.RedirectContext;
import com.linecorp.armeria.common.CompletableHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;

class RedirectContextTest {

    @Test
    void buildOriginalUri() {
        HttpStatus status;
        final CompletableHttpResponse response = HttpResponse.defer();

        HttpRequest request = request(HttpHeaders.of(HttpHeaderNames.AUTHORITY, "foo"));
        RedirectContext redirectCtx = new RedirectContext(ClientRequestContext.of(request), request,
                                                          response);
        assertThat(redirectCtx.originalUri()).isEqualTo("http://foo:80/path");

        request = request(HttpHeaders.of(HttpHeaderNames.AUTHORITY, "foo:8080"));
        redirectCtx = new RedirectContext(ClientRequestContext.of(request), request, response);
        assertThat(redirectCtx.originalUri()).isEqualTo("http://foo:8080/path");

        request = request(HttpHeaders.of(HttpHeaderNames.AUTHORITY, "[::1]"));
        redirectCtx = new RedirectContext(ClientRequestContext.of(request), request, response);
        assertThat(redirectCtx.originalUri()).isEqualTo("http://[::1]:80/path");

        request = request(HttpHeaders.of(HttpHeaderNames.AUTHORITY, "[::1]:8080"));
        redirectCtx = new RedirectContext(ClientRequestContext.of(request), request, response);
        assertThat(redirectCtx.originalUri()).isEqualTo("http://[::1]:8080/path");

        // Use different session protocols.
        request = request(HttpHeaders.of(HttpHeaderNames.AUTHORITY, "foo"));
        redirectCtx = new RedirectContext(ClientRequestContext.builder(request)
                                                              .sessionProtocol(SessionProtocol.H1)
                                                              .sslSession(newSslSession())
                                                              .build(), request, response);
        assertThat(redirectCtx.originalUri()).isEqualTo("https://foo:443/path");

        request = request(HttpHeaders.of(HttpHeaderNames.AUTHORITY, "foo"));
        redirectCtx = new RedirectContext(ClientRequestContext.builder(request)
                                                              .sessionProtocol(SessionProtocol.H1C)
                                                              .build(), request, response);
        assertThat(redirectCtx.originalUri()).isEqualTo("http://foo:80/path");

        request = request(HttpHeaders.of());
        redirectCtx = new RedirectContext(ClientRequestContext.builder(request)
                                                              .sessionProtocol(SessionProtocol.H1C)
                                                              .build(), request, response);
        // Use endpoint host.
        assertThat(redirectCtx.originalUri()).isEqualTo("http://127.0.0.1:80/path");
    }

    private static HttpRequest request(HttpHeaders headers) {
        return HttpRequest.of(RequestHeaders.builder(HttpMethod.GET, "/path")
                                            .add(headers)
                                            .build());
    }

    private static SSLSession newSslSession() {
        final SSLSession sslSession = mock(SSLSession.class, withSettings().lenient());
        when(sslSession.getId()).thenReturn(new byte[] { 1, 1, 2, 3, 5, 8, 13, 21 });
        when(sslSession.getProtocol()).thenReturn("TLSv1.2");
        when(sslSession.getCipherSuite()).thenReturn("some-cipher");
        return sslSession;
    }
}
