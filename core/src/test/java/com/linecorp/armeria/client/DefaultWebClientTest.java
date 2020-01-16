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
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;

class DefaultWebClientTest {

    @Test
    void testConcatenateRequestPath() throws Exception {
        final String clientUriPath = "http://127.0.0.1/hello";
        final String requestPath = "world/test?q1=foo";

        final HttpClient mockClientDelegate = mock(HttpClient.class);
        final DefaultWebClient defaultWebClient = createDefaultWebClient(clientUriPath, mockClientDelegate
        );

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
        final DefaultWebClient defaultWebClient = createDefaultWebClient(clientUriPath, mockClientDelegate
        );

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
        final DefaultWebClient defaultWebClient = createDefaultWebClient(clientUriPath, mockClientDelegate
        );

        defaultWebClient.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, clientUriPath)));

        final ArgumentCaptor<HttpRequest> argCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(mockClientDelegate).execute(any(ClientRequestContext.class), argCaptor.capture());

        final String concatPath = argCaptor.getValue().path();
        assertThat(concatPath).isEqualTo("/helloWorld/test");
    }

    private static DefaultWebClient createDefaultWebClient(
            String clientUriPath, HttpClient mockClientDelegate) throws URISyntaxException {
        final ClientBuilderParams clientBuilderParams = ClientBuilderParams.of(
                ClientFactory.ofDefault(), new URI(clientUriPath), WebClient.class, ClientOptions.of());
        return new DefaultWebClient(
                clientBuilderParams, mockClientDelegate, NoopMeterRegistry.get());
    }

    @Test
    void endpointRemapper() {
        final EndpointGroup group = EndpointGroup.of(Endpoint.of("127.0.0.1", 1),
                                                     Endpoint.of("127.0.0.1", 1));
        final WebClient client = WebClient.builder("http://group")
                                          .endpointRemapper(endpoint -> {
                                              if ("group".equals(endpoint.host())) {
                                                  return group;
                                              } else {
                                                  return endpoint;
                                              }
                                          })
                                          .build();

        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            client.get("/").drainAll();
            final ClientRequestContext cctx = ctxCaptor.get();
            await().untilAsserted(() -> {
                assertThat(cctx.endpointGroup()).isSameAs(group);
                assertThat(cctx.endpoint()).isEqualTo(Endpoint.of("127.0.0.1", 1));
                assertThat(cctx.request().authority()).isEqualTo("127.0.0.1:1");
            });
        }
    }

    @Test
    void endpointRemapperForUnspecifiedUri() {
        final EndpointGroup group = EndpointGroup.of(Endpoint.of("127.0.0.1", 1),
                                                     Endpoint.of("127.0.0.1", 1));
        final WebClient client = WebClient.builder()
                                          .endpointRemapper(endpoint -> {
                                              if ("group".equals(endpoint.host())) {
                                                  return group;
                                              } else {
                                                  return endpoint;
                                              }
                                          })
                                          .build();

        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            client.get("http://group").drainAll();
            final ClientRequestContext cctx = ctxCaptor.get();
            await().untilAsserted(() -> {
                assertThat(cctx.endpointGroup()).isSameAs(group);
                assertThat(cctx.endpoint()).isEqualTo(Endpoint.of("127.0.0.1", 1));
                assertThat(cctx.request().authority()).isEqualTo("127.0.0.1:1");
            });
        }
    }
}
