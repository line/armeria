/*
 * Copyright 2017 LINE Corporation
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;

class DefaultWebClientTest {

    @Test
    void testConcatenateRequestPath() throws Exception {
        final String clientUriPath = "http://127.0.0.1/hello";
        final String requestPath = "world/test?q1=foo";

        final HttpClient mockClientDelegate = mock(HttpClient.class);
        final DefaultWebClient defaultWebClient = createDefaultWebClient(clientUriPath, mockClientDelegate,
                "127.0.0.1");

        defaultWebClient.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, requestPath)));

        final ArgumentCaptor<HttpRequest> argCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClientDelegate).execute(any(ClientRequestContext.class), argCaptor.capture());

        final String concatPath = argCaptor.getValue().path();
        assertThat(concatPath).isEqualTo("/hello/world/test?q1=foo");
    }

    @Test
    void testRequestParamsUndefinedEndPoint() throws Exception {
        final String clientUriPath = "http://127.0.0.1/helloWorld/test?q1=foo";
        final HttpClient mockClientDelegate = mock(HttpClient.class);
        final DefaultWebClient defaultWebClient = createDefaultWebClient(clientUriPath, mockClientDelegate,
                "undefined");

        defaultWebClient.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, clientUriPath)));

        final ArgumentCaptor<HttpRequest> argCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClientDelegate).execute(any(ClientRequestContext.class), argCaptor.capture());

        final String concatPath = argCaptor.getValue().path();
        assertThat(concatPath).isEqualTo("/helloWorld/test?q1=foo");
    }

    @Test
    void testWithoutRequestParamsUndefinedEndPoint() throws Exception {
        final String clientUriPath = "http://127.0.0.1/helloWorld/test";
        final HttpClient mockClientDelegate = mock(HttpClient.class);
        final DefaultWebClient defaultWebClient = createDefaultWebClient(clientUriPath, mockClientDelegate,
                "undefined");

        defaultWebClient.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, clientUriPath)));

        final ArgumentCaptor<HttpRequest> argCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClientDelegate).execute(any(ClientRequestContext.class), argCaptor.capture());

        final String concatPath = argCaptor.getValue().path();
        assertThat(concatPath).isEqualTo("/helloWorld/test");
    }

    private DefaultWebClient createDefaultWebClient(String clientUriPath, HttpClient mockClientDelegate,
                                                    String endpoint) throws URISyntaxException {
        final ClientBuilderParams clientBuilderParams = new DefaultClientBuilderParams(
                ClientFactory.ofDefault(), new URI(clientUriPath), WebClient.class, ClientOptions.of());
        return new DefaultWebClient(
                clientBuilderParams, mockClientDelegate, NoopMeterRegistry.get(),
                SessionProtocol.of("http"), Endpoint.of(endpoint));
    }

    @Test
    void requestAbortPropagatesException() {
        final HttpRequestWriter req = HttpRequest.streaming(HttpMethod.GET, "/");
        req.abort(new IllegalStateException("closed"));
        assertThatThrownBy(() -> req.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
