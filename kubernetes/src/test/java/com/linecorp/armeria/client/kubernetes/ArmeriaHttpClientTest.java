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
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.http.AsyncBody;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import io.fabric8.kubernetes.client.http.WebSocket;
import io.fabric8.kubernetes.client.http.WebSocket.Listener;
import io.fabric8.kubernetes.client.http.WebSocketHandshakeException;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;

/**
 * Tests a {@link HttpClient} at or below the {@link KubernetesClient} level.
 */
class ArmeriaHttpClientTest {

    // Forked from https://github.com/ikhoon/kubernetes-client/blob/main/httpclient-tests/src/test/java/io/fabric8/kubernetes/client/http/OkHttpClientTest.java

    private KubernetesMockServer server;
    private KubernetesClient client;

    HttpClient.Factory getHttpClientFactory() {
        return new ArmeriaHttpClientFactory();
    }

    @BeforeEach
    void setUp() {
        server = new KubernetesMockServer();
        server.start();
        client = server.createClient(getHttpClientFactory());
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.shutdown();
    }

    @Test
    void testMultipleClosure() {
        client.getHttpClient().close();
        client.getHttpClient().close(); // additional close should be no-op
    }

    @Test
    void testWebsocketClientClose() throws Exception {
        server.expect().withPath("/wsclose")
              .andUpgradeToWebSocket()
              .open()
              .expect("never sent").andEmit("response").always().done().always();

        final CompletableFuture<String> closeReason = new CompletableFuture<>();

        final CompletableFuture<WebSocket> startedFuture =
                client.getHttpClient().newWebSocketBuilder()
                      .uri(URI.create(webSocketMasterUrl() + "wsclose"))
                      .buildAsync(new Listener() {
                          @Override
                          public void onClose(WebSocket webSocket, int code, String reason) {
                              closeReason.complete(reason);
                          }
                      });

        startedFuture.thenAccept(w -> w.sendClose(1000, "I'm done"));

        assertEquals("I'm done", closeReason.get(10, TimeUnit.SECONDS));
    }

    @Test
    void testWebsocketHandshakeFailure() {
        final CompletableFuture<WebSocket> startedFuture = client.getHttpClient().newWebSocketBuilder()
                                                                 .uri(URI.create(webSocketMasterUrl()))
                                                                 .buildAsync(new Listener() {});

        assertThrows(WebSocketHandshakeException.class, () -> {
            try {
                startedFuture.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw e.getCause();
            }
        });
    }

    @Test
    void testOnOpen() throws Exception {
        server.expect().withPath("/foo")
              .andUpgradeToWebSocket()
              .open()
              .done().always();

        final CompletableFuture<Boolean> opened = new CompletableFuture<>();
        final CompletableFuture<Boolean> future =
                client.getHttpClient().newWebSocketBuilder()
                      .uri(URI.create(webSocketMasterUrl() + "foo"))
                      .buildAsync(new Listener() {

                          @Override
                          public void onOpen(WebSocket webSocket) {
                              opened.complete(true);
                          }
                      })
                      .thenCompose(ws -> opened);

        assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
    }

    private String webSocketMasterUrl() {
        // The mock WebServer can't handle WebSocket over HTTP/2.
        return client.getConfiguration().getMasterUrl().replace("https", "ws+h1");
    }

    @Test
    void testAsyncBody() throws Exception {
        final int byteCount = 20000;
        server.expect().withPath("/async").andReturn(200,
                                                     new String(new byte[byteCount], StandardCharsets.UTF_8))
              .always();

        final CompletableFuture<Integer> consumed = new CompletableFuture<>();
        final AtomicInteger total = new AtomicInteger();

        final CompletableFuture<HttpResponse<AsyncBody>> responseFuture = client.getHttpClient().consumeBytes(
                client.getHttpClient().newHttpRequestBuilder()
                      .uri(URI.create(client.getConfiguration().getMasterUrl() + "async"))
                      .build(),
                (value, asyncBody) -> {
                    value.stream().map(ByteBuffer::remaining).forEach(total::addAndGet);
                    asyncBody.consume();
                });

        responseFuture.whenComplete((r, t) -> {
            if (t != null) {
                consumed.completeExceptionally(t);
            }
            if (r != null) {
                r.body().consume();
                r.body().done().whenComplete((v, ex) -> {
                    if (ex != null) {
                        consumed.completeExceptionally(ex);
                    } else {
                        consumed.complete(total.get());
                    }
                });
            }
        });

        assertEquals(byteCount, consumed.get(10, TimeUnit.SECONDS));
    }

    @DisplayName("Supported response body types")
    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(classes = { String.class, byte[].class, Reader.class, InputStream.class })
    void supportedResponseBodyTypes(Class<?> type) throws Exception {
        final int length = 16384;
        final String value = new String(new byte[length]);
        server.expect().withPath("/type").andReturn(200, value).always();
        final HttpRequest request = client.getHttpClient().newHttpRequestBuilder()
                                          .uri(URI.create(client.getConfiguration().getMasterUrl() + "type"))
                                          .build();
        final HttpResponse<?> result = client.getHttpClient().sendAsync(request, type)
                                             .get(10, TimeUnit.SECONDS);
        assertThat(result)
                .satisfies(r -> assertThat(r.headers("Content-Length")).first()
                                                                       .isEqualTo(String.valueOf(length)))
                .satisfies(r -> assertThat(r.body()).isInstanceOf(type))
                .satisfies(r -> assertThat(r.bodyString()).isEqualTo(value));
    }

    @Test
    void supportedResponseTypeWithInvalid() {
        final HttpClient httpClient = client.getHttpClient();
        final HttpRequest request = httpClient.newHttpRequestBuilder()
                                              .uri(URI.create(
                                                      client.getConfiguration().getMasterUrl() + "type"))
                                              .build();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> httpClient.sendAsync(request, Boolean.class))
                .withMessageStartingWith("Unsupported response type: ")
                .withMessageContaining("Boolean");
    }
}
