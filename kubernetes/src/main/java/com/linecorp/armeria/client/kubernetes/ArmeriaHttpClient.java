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

package com.linecorp.armeria.client.kubernetes;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestBuilder;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.fabric8.kubernetes.client.http.AsyncBody;
import io.fabric8.kubernetes.client.http.HttpResponse;
import io.fabric8.kubernetes.client.http.StandardHttpClient;
import io.fabric8.kubernetes.client.http.StandardHttpRequest;
import io.fabric8.kubernetes.client.http.StandardWebSocketBuilder;
import io.fabric8.kubernetes.client.http.WebSocket;
import io.fabric8.kubernetes.client.http.WebSocketResponse;

/**
 * Armeria based implementation of {@link StandardHttpClient}.
 */
final class ArmeriaHttpClient
        extends StandardHttpClient<ArmeriaHttpClient, ArmeriaHttpClientFactory, ArmeriaHttpClientBuilder> {

    private final WebClient webClient;
    private final ArmeriaWebSocketClient webSocketClient;

    ArmeriaHttpClient(ArmeriaHttpClientBuilder armeriaHttpClientBuilder, WebClient webClient) {
        super(armeriaHttpClientBuilder, new AtomicBoolean());
        this.webClient = webClient;
        webSocketClient = new ArmeriaWebSocketClient(armeriaHttpClientBuilder);
    }

    @Override
    public void doClose() {
        webClient.options().factory().close();
        webSocketClient.close();
    }

    @Override
    public CompletableFuture<WebSocketResponse> buildWebSocketDirect(
            StandardWebSocketBuilder standardWebSocketBuilder, WebSocket.Listener listener) {
        return webSocketClient.execute(standardWebSocketBuilder, listener);
    }

    @Override
    public CompletableFuture<HttpResponse<AsyncBody>> consumeBytesDirect(
            StandardHttpRequest request, AsyncBody.Consumer<List<ByteBuffer>> consumer) {
        final HttpRequest armeriaRequest = toArmeriaRequest(request);
        RequestOptions requestOptions = RequestOptions.of();
        if (request.getTimeout() != null) {
            requestOptions = RequestOptions.builder()
                                           .responseTimeout(request.getTimeout())
                                           .build();
        }

        final SplitHttpResponse splitResponse;
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final com.linecorp.armeria.common.HttpResponse response = webClient.execute(armeriaRequest,
                                                                                        requestOptions);
            ctx = captor.get();
            splitResponse = response.split(ctx.eventLoop());
        }

        return splitResponse.headers().thenApply(responseHeaders -> {
            final AsyncBodySubscriber subscriber = new AsyncBodySubscriber(consumer);
            splitResponse.body().subscribe(subscriber, ctx.eventLoop());
            return new ArmeriaHttpResponse(request, responseHeaders, subscriber);
        });
    }

    private static HttpRequest toArmeriaRequest(StandardHttpRequest request) {
        final HttpRequestBuilder requestBuilder =
                HttpRequest.builder()
                           .method(HttpMethod.valueOf(request.method()))
                           .path(request.uri().toString());

        if (request.isExpectContinue()) {
            // Set `Expect: 100-continue` header to the request headers when it is supported by Armeria.
            // https://github.com/line/armeria/issues/5394
        }

        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            final String headerName = entry.getKey();
            for (String headerValue : entry.getValue()) {
                requestBuilder.header(headerName, headerValue);
            }
        }
        MediaType contentType = null;
        if (request.getContentType() != null) {
            contentType = MediaType.parse(request.getContentType());
        }

        final StandardHttpRequest.BodyContent body = request.body();
        if (body != null) {
            if (body instanceof StandardHttpRequest.StringBodyContent) {
                if (contentType == null) {
                    contentType = MediaType.PLAIN_TEXT_UTF_8;
                }
                requestBuilder.content(contentType,
                                       ((StandardHttpRequest.StringBodyContent) body).getContent());
            } else if (body instanceof StandardHttpRequest.ByteArrayBodyContent) {
                if (contentType == null) {
                    contentType = MediaType.OCTET_STREAM;
                }
                final byte[] content = ((StandardHttpRequest.ByteArrayBodyContent) body).getContent();
                requestBuilder.content(contentType, HttpData.wrap(content));
            } else if (body instanceof StandardHttpRequest.InputStreamBodyContent) {
                if (contentType == null) {
                    contentType = MediaType.OCTET_STREAM;
                }
                final StandardHttpRequest.InputStreamBodyContent bodyContent =
                        (StandardHttpRequest.InputStreamBodyContent) body;
                requestBuilder.content(contentType, StreamMessage.of(bodyContent.getContent()));
            } else {
                throw new IllegalArgumentException("Unsupported body content");
            }
        }
        return requestBuilder.build();
    }

    WebClient getWebClient() {
        return webClient;
    }
}
