/*
 * Copyright 2023 LINE Corporation
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
/*
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.client.kubernetes;

import static io.fabric8.kubernetes.client.utils.HttpClientUtils.basicCredentials;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.UnprocessedRequestException;

import io.fabric8.kubernetes.client.http.AbstractHttpClientProxyTest;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.http.StandardHttpHeaders;
import io.fabric8.mockwebserver.DefaultMockServer;
import io.fabric8.mockwebserver.http.Headers;
import io.fabric8.mockwebserver.http.RecordedRequest;
import io.fabric8.mockwebserver.utils.ResponseProvider;

class ArmeriaHttpClientProxyTest extends AbstractHttpClientProxyTest {

    private static DefaultMockServer server;

    @BeforeAll
    static void beforeAll() {
        server = new DefaultMockServer(false);
        server.start();
    }

    @AfterAll
    static void afterAll() {
        server.shutdown();
    }

    @Override
    protected HttpClient.Factory getHttpClientFactory() {
        return new ArmeriaHttpClientFactory();
    }

    /**
     * Forked the upstream test case and wrapped client.sendAsync() with try-catch.
     */
    @Override
    @Test
    @DisplayName("Proxied HttpClient with basic authorization adds required headers to the request")
    protected void proxyConfigurationBasicAuthAddsRequiredHeaders() throws Exception {
        server.expect().get().withPath("/").andReply(new ResponseProvider<Object>() {
            @Override
            public String getBody(RecordedRequest request) {
                return "\n";
            }

            @Override
            public void setHeaders(Headers headers) {
            }

            @Override
            public int getStatusCode(RecordedRequest request) {
                return request.getHeader(StandardHttpHeaders.PROXY_AUTHORIZATION) != null ? 200 : 407;
            }

            @Override
            public Headers getHeaders() {
                return new Headers.Builder().add("Proxy-Authenticate", "Basic").build();
            }
        }).always();
        // Given
        final HttpClient.Builder builder =
                getHttpClientFactory().newBuilder()
                                      .proxyAddress(new InetSocketAddress("localhost", server.getPort()))
                                      .proxyAuthorization(basicCredentials("auth", "cred"));
        try (HttpClient client = builder.build()) {
            // When
            try {
                client.sendAsync(client.newHttpRequestBuilder()
                                       .uri(String.format("http://0.0.0.0:%s/not-found", server.getPort()))
                                       .build(), String.class)
                      .get(10L, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                // HttpProxyHandler raises an exception when the response is not 200 OK.
                assertThat(e.getCause()).isInstanceOf(UnprocessedRequestException.class);
            }
            // Then
            assertThat(server.getLastRequest())
                    .extracting(RecordedRequest::getHeaders)
                    .returns("0.0.0.0:" + server.getPort(), h -> h.get("Host"))
                    .returns("Basic YXV0aDpjcmVk", h -> h.get("Proxy-Authorization"));
        }
    }

    /**
     * Forked the upstream test case and wrapped client.sendAsync() with try-catch.
     */
    @Override
    @Test
    @DisplayName("Proxied HttpClient adds required headers to the request")
    protected void proxyConfigurationOtherAuthAddsRequiredHeaders() throws Exception {
        // Given
        final HttpClient.Builder builder =
                getHttpClientFactory()
                        .newBuilder()
                        .proxyAddress(new InetSocketAddress("localhost", server.getPort()))
                        .proxyAuthorization("Other kind of auth");
        try (HttpClient client = builder.build()) {
            // When
            try {
                client.sendAsync(client.newHttpRequestBuilder()
                                       .uri(String.format("http://0.0.0.0:%s/not-found", server.getPort()))
                                       .build(), String.class)
                      .get(10L, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                // HttpProxyHandler raises an exception when the response is not 200 OK.
                assertThat(e.getCause()).isInstanceOf(UnprocessedRequestException.class);
            }
            // Then
            assertThat(server.getLastRequest())
                    .extracting(RecordedRequest::getHeaders)
                    .extracting(Headers::toMultimap)
                    .hasFieldOrPropertyWithValue("host", ImmutableList.of("0.0.0.0:" + server.getPort()))
                    .hasFieldOrPropertyWithValue("proxy-authorization", ImmutableList.of("Other kind of auth"));
        }
    }
}
