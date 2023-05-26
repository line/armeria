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

package com.linecorp.armeria.client.websocket;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.AbstractWebClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;

/**
 * Builds a {@link WebSocketClient}.
 */
@UnstableApi
public final class WebSocketClientBuilder extends AbstractWebClientBuilder {

    static final int DEFAULT_MAX_FRAME_PAYLOAD_LENGTH = 65535; // 64 * 1024 -1

    private int maxFramePayloadLength = DEFAULT_MAX_FRAME_PAYLOAD_LENGTH;
    private boolean allowMaskMismatch;

    WebSocketClientBuilder(URI uri) {
        super(uri, true);
        setWebSocketDefaultOption();
    }

    WebSocketClientBuilder(Scheme scheme, EndpointGroup endpointGroup, @Nullable String path) {
        super(validateSerializationFormat(requireNonNull(scheme, "scheme").serializationFormat()),
              scheme.sessionProtocol(), endpointGroup, path);
        setWebSocketDefaultOption();
    }

    private static SerializationFormat validateSerializationFormat(SerializationFormat serializationFormat) {
        requireNonNull(serializationFormat, "serializationFormat");
        checkArgument(serializationFormat == SerializationFormat.WS,
                      "serializationFormat: %s (expected: %s)", serializationFormat, SerializationFormat.WS);
        return serializationFormat;
    }

    private void setWebSocketDefaultOption() {
        responseTimeoutMillis(0);
        maxResponseLength(0);
        requestAutoAbortDelayMillis(5000);
        addOriginHeader(true);
    }

    /**
     * Sets the maximum length of a frame's payload.
     * {@value DEFAULT_MAX_FRAME_PAYLOAD_LENGTH} is used by default.
     */
    public WebSocketClientBuilder maxFramePayloadLength(int maxFramePayloadLength) {
        checkArgument(maxFramePayloadLength > 0,
                      "maxFramePayloadLength: %s (expected: > 0)", maxFramePayloadLength);
        this.maxFramePayloadLength = maxFramePayloadLength;
        return this;
    }

    /**
     * Sets whether the decoder allows to loosen the masking requirement on received frames.
     * It's not allowed by default.
     */
    public WebSocketClientBuilder allowMaskMismatch(boolean allowMaskMismatch) {
        this.allowMaskMismatch = allowMaskMismatch;
        return this;
    }

    /**
     * Sets whether to add an {@link HttpHeaderNames#ORIGIN} header automatically when sending
     * an {@link HttpRequest} when the {@link HttpRequest#headers()} does not have it.
     * It's {@code true} by default.
     */
    public WebSocketClientBuilder addOriginHeader(boolean addOriginHeader) {
        // Consider promoting this to AbstractClientOptionsBuilder.
        option(ClientOptions.ADD_ORIGIN_HEADER, addOriginHeader);
        return this;
    }

    /**
     * Returns a newly-created {@link WebSocketClient} based on the properties of this builder.
     */
    public WebSocketClient build() {
        final WebClient webClient = buildWebClient();
        return new DefaultWebSocketClient(webClient, maxFramePayloadLength, allowMaskMismatch);
    }

    // Override the return type of the chaining methods in the superclass.

    @Deprecated
    @Override
    public WebSocketClientBuilder rpcDecorator(Function<? super RpcClient, ? extends RpcClient> decorator) {
        return (WebSocketClientBuilder) super.rpcDecorator(decorator);
    }

    @Deprecated
    @Override
    public WebSocketClientBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        return (WebSocketClientBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public WebSocketClientBuilder options(ClientOptions options) {
        return (WebSocketClientBuilder) super.options(options);
    }

    @Override
    public WebSocketClientBuilder options(ClientOptionValue<?>... options) {
        return (WebSocketClientBuilder) super.options(options);
    }

    @Override
    public WebSocketClientBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (WebSocketClientBuilder) super.options(options);
    }

    @Override
    public <T> WebSocketClientBuilder option(ClientOption<T> option, T value) {
        return (WebSocketClientBuilder) super.option(option, value);
    }

    @Override
    public <T> WebSocketClientBuilder option(ClientOptionValue<T> optionValue) {
        return (WebSocketClientBuilder) super.option(optionValue);
    }

    @Override
    public WebSocketClientBuilder factory(ClientFactory factory) {
        return (WebSocketClientBuilder) super.factory(factory);
    }

    @Override
    public WebSocketClientBuilder writeTimeout(Duration writeTimeout) {
        return (WebSocketClientBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public WebSocketClientBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (WebSocketClientBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public WebSocketClientBuilder responseTimeout(Duration responseTimeout) {
        return (WebSocketClientBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public WebSocketClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (WebSocketClientBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public WebSocketClientBuilder maxResponseLength(long maxResponseLength) {
        return (WebSocketClientBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public WebSocketClientBuilder requestAutoAbortDelay(Duration delay) {
        return (WebSocketClientBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public WebSocketClientBuilder requestAutoAbortDelayMillis(long delayMillis) {
        return (WebSocketClientBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public WebSocketClientBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (WebSocketClientBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public WebSocketClientBuilder successFunction(SuccessFunction successFunction) {
        return (WebSocketClientBuilder) super.successFunction(successFunction);
    }

    @Override
    public WebSocketClientBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (WebSocketClientBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public WebSocketClientBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (WebSocketClientBuilder) super.decorator(decorator);
    }

    @Override
    public WebSocketClientBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (WebSocketClientBuilder) super.decorator(decorator);
    }

    @Override
    public WebSocketClientBuilder clearDecorators() {
        return (WebSocketClientBuilder) super.clearDecorators();
    }

    @Override
    public WebSocketClientBuilder addHeader(CharSequence name, Object value) {
        return (WebSocketClientBuilder) super.addHeader(name, value);
    }

    @Override
    public WebSocketClientBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (WebSocketClientBuilder) super.addHeaders(headers);
    }

    @Override
    public WebSocketClientBuilder setHeader(CharSequence name, Object value) {
        return (WebSocketClientBuilder) super.setHeader(name, value);
    }

    @Override
    public WebSocketClientBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (WebSocketClientBuilder) super.setHeaders(headers);
    }

    @Override
    public WebSocketClientBuilder auth(BasicToken token) {
        return (WebSocketClientBuilder) super.auth(token);
    }

    @Override
    public WebSocketClientBuilder auth(OAuth1aToken token) {
        return (WebSocketClientBuilder) super.auth(token);
    }

    @Override
    public WebSocketClientBuilder auth(OAuth2Token token) {
        return (WebSocketClientBuilder) super.auth(token);
    }

    @Override
    public WebSocketClientBuilder auth(AuthToken token) {
        return (WebSocketClientBuilder) super.auth(token);
    }

    @Override
    public WebSocketClientBuilder followRedirects() {
        return (WebSocketClientBuilder) super.followRedirects();
    }

    @Override
    public WebSocketClientBuilder followRedirects(RedirectConfig redirectConfig) {
        return (WebSocketClientBuilder) super.followRedirects(redirectConfig);
    }

    @Override
    public WebSocketClientBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        return (WebSocketClientBuilder) super.contextCustomizer(contextCustomizer);
    }
}
