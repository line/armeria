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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.fabric8.kubernetes.client.http.AbstractInterceptorTest;
import io.fabric8.kubernetes.client.http.BasicBuilder;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import io.fabric8.kubernetes.client.http.Interceptor;
import io.fabric8.kubernetes.client.http.WebSocket;
import io.fabric8.mockwebserver.DefaultMockServer;

class ArmeriaHttpInterceptorTest extends AbstractInterceptorTest {

    private static DefaultMockServer mockServer;

    @BeforeEach
    void startMockServer() {
        mockServer = new DefaultMockServer(false);
        mockServer.start();
    }

    @AfterEach
    void stopMockServer() {
        mockServer.shutdown();
    }

    @Override
    protected HttpClient.Factory getHttpClientFactory() {
        return new ArmeriaHttpClientFactory();
    }

    /**
     * Forked the upstream test case and modified the assertion for the request count.
     * As Armeria sends an upgrade request in the first place, mockServer.getRequestCount() is 3.
     */
    @Override
    @Test
    @DisplayName("before (WS), should modify the HTTP request URI")
    public void beforeWsModifiesRequestUri() throws Exception {
        // Given
        mockServer.expect().withPath("/valid-url")
                  .andUpgradeToWebSocket()
                  .open().done().always();
        final HttpClient.Builder builder =
                getHttpClientFactory().newBuilder().addOrReplaceInterceptor(
                        "test", new Interceptor() {
                            @Override
                            public void before(BasicBuilder builder, HttpRequest request, RequestTags tags) {
                                builder.uri(URI.create(mockServer.url("valid-url")));
                            }
                        });
        try (HttpClient client = builder.build()) {
            // When
            client.newWebSocketBuilder()
                  .uri(URI.create(mockServer.url("invalid-url")))
                  .buildAsync(new WebSocket.Listener() {
                  }).get(10L, TimeUnit.SECONDS);
        }
        // Then
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
        assertThat(mockServer.getLastRequest().getPath()).isEqualTo("/valid-url");
    }

    /**
     * Forked the upstream test case and modified the assertion for the request count.
     * As Armeria sends an upgrade request in the first place, mockServer.getRequestCount() is 2.
     */
    @Override
    @Test
    @DisplayName("before (HTTP), should modify the HTTP request URI")
    public void beforeHttpModifiesRequestUri() throws Exception {
        // Given
        final HttpClient.Builder builder =
                getHttpClientFactory().newBuilder().addOrReplaceInterceptor("test", new Interceptor() {
                    @Override
                    public void before(BasicBuilder builder, HttpRequest request, RequestTags tags) {
                        builder.uri(URI.create(mockServer.url("valid-url")));
                    }
                });
        // When
        try (HttpClient client = builder.build()) {
            client.sendAsync(client.newHttpRequestBuilder().uri(mockServer.url("/invalid-url")).build(),
                             String.class)
                  .get(10L, TimeUnit.SECONDS);
        }
        // Then
        assertThat(mockServer.getRequestCount()).isEqualTo(2);
        assertThat(mockServer.getLastRequest().getPath()).isEqualTo("/valid-url");
    }

    /**
     * Forked the upstream test case and modified the assertion for the request count.
     * As Armeria sends an upgrade request in the first place, mockServer.getRequestCount() is 3.
     */
    @Override
    @Test
    @DisplayName("afterFailure (WS), replaces the URL and returns true, should reconnect to valid URL")
    public void afterWSFailureTODOReplacesResponseInSendAsync() throws Exception {
        // Given
        mockServer.expect().withPath("/valid-url")
                  .andUpgradeToWebSocket()
                  .open().done().always();
        final HttpClient.Builder builder = getHttpClientFactory().newBuilder().addOrReplaceInterceptor(
                "test", new Interceptor() {
                    @Override
                    public CompletableFuture<Boolean> afterFailure(BasicBuilder builder,
                            HttpResponse<?> response, RequestTags tags) {
                        builder.uri(URI.create(mockServer.url("valid-url")));
                        return UnmodifiableFuture.completedFuture(true);
                    }
                });
        try (HttpClient client = builder.build()) {
            // When
            client.newWebSocketBuilder()
                  .uri(URI.create(mockServer.url("invalid-url")))
                  .buildAsync(new WebSocket.Listener() {
                  }).get(10L, TimeUnit.SECONDS);
        }
        // Then
        assertThat(mockServer.getRequestCount()).isEqualTo(3);
        assertThat(mockServer.getLastRequest().getPath()).isEqualTo("/valid-url");
    }
}
