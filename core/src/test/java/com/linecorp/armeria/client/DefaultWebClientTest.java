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
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;

class DefaultWebClientTest {

    @Test
    void testConcatenateRequestPath() {
        final String clientUriPath = "http://127.0.0.1/hello";
        final String requestPath = "world/test?q1=foo";
        final WebClient client = WebClient.of(clientUriPath);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, requestPath))).aggregate();
            assertThat(captor.get().request().path()).isEqualTo("/hello/world/test?q1=foo");
        }
    }

    @Test
    void testRequestParamsUndefinedEndPoint() {
        final String path = "http://127.0.0.1/helloWorld/test?q1=foo";
        final WebClient client = WebClient.of(AbstractWebClientBuilder.UNDEFINED_URI);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, path))).aggregate();
            assertThat(captor.get().request().path()).isEqualTo("/helloWorld/test?q1=foo");
        }
    }

    @Test
    void testWithoutRequestParamsUndefinedEndPoint() {
        final String path = "http://127.0.0.1/helloWorld/test";
        final WebClient client = WebClient.of(AbstractWebClientBuilder.UNDEFINED_URI);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.execute(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, path))).aggregate();
            assertThat(captor.get().request().path()).isEqualTo("/helloWorld/test");
        }
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
            client.get("/").aggregate();
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
            client.get("http://group").aggregate();
            final ClientRequestContext cctx = ctxCaptor.get();
            await().untilAsserted(() -> {
                assertThat(cctx.endpointGroup()).isSameAs(group);
                assertThat(cctx.endpoint()).isEqualTo(Endpoint.of("127.0.0.1", 1));
                assertThat(cctx.request().authority()).isEqualTo("127.0.0.1:1");
            });
        }
    }

    @Test
    void testWithQueryParams() {
        final String path = "http://127.0.0.1/helloWorld/test";
        final QueryParams queryParams = QueryParams.builder()
                                                   .add("q1", "foo")
                                                   .build();
        final WebClient client = WebClient.of(AbstractWebClientBuilder.UNDEFINED_URI);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.get(path, queryParams).aggregate();
            assertThat(captor.get().request().path()).isEqualTo("/helloWorld/test?q1=foo");
        }

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.post(path, "", queryParams).aggregate();
            assertThat(captor.get().request().path()).isEqualTo("/helloWorld/test?q1=foo");
        }
    }
}
