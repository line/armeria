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

import java.net.URI;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;

/**
 * Creates a new web client that connects to the specified {@link URI} using the builder pattern.
 * Use the factory methods in {@link WebClient} if you do not have many options to override.
 * Please refer to {@link ClientBuilder} for how decorators and HTTP headers are configured
 */
public final class WebClientBuilder extends AbstractWebClientBuilder {

    /**
     * Creates a new instance.
     */
    WebClientBuilder() {}

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    WebClientBuilder(URI uri) {
        super(uri);
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the {@code sessionProtocol} is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    WebClientBuilder(SessionProtocol sessionProtocol, EndpointGroup endpointGroup, @Nullable String path) {
        super(sessionProtocol, endpointGroup, path);
    }

    /**
     * Returns a newly-created web client based on the properties of this builder.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link WebClient#builder(String)} or
     *                                  {@link WebClient#builder(URI)} is not an HTTP scheme
     */
    public WebClient build() {
        return buildWebClient();
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public WebClientBuilder rpcDecorator(Function<? super RpcClient, ? extends RpcClient> decorator) {
        return (WebClientBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public WebClientBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        return (WebClientBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public WebClientBuilder options(ClientOptions options) {
        return (WebClientBuilder) super.options(options);
    }

    @Override
    public WebClientBuilder options(ClientOptionValue<?>... options) {
        return (WebClientBuilder) super.options(options);
    }

    @Override
    public WebClientBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (WebClientBuilder) super.options(options);
    }

    @Override
    public <T> WebClientBuilder option(ClientOption<T> option, T value) {
        return (WebClientBuilder) super.option(option, value);
    }

    @Override
    public <T> WebClientBuilder option(ClientOptionValue<T> optionValue) {
        return (WebClientBuilder) super.option(optionValue);
    }

    @Override
    public WebClientBuilder factory(ClientFactory factory) {
        return (WebClientBuilder) super.factory(factory);
    }

    @Override
    public WebClientBuilder writeTimeout(Duration writeTimeout) {
        return (WebClientBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public WebClientBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (WebClientBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public WebClientBuilder responseTimeout(Duration responseTimeout) {
        return (WebClientBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public WebClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (WebClientBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public WebClientBuilder maxResponseLength(long maxResponseLength) {
        return (WebClientBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public WebClientBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (WebClientBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public WebClientBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (WebClientBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public WebClientBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (WebClientBuilder) super.decorator(decorator);
    }

    @Override
    public WebClientBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (WebClientBuilder) super.decorator(decorator);
    }

    @Override
    public WebClientBuilder clearDecorators() {
        return (WebClientBuilder) super.clearDecorators();
    }

    @Override
    public WebClientBuilder addHeader(CharSequence name, Object value) {
        return (WebClientBuilder) super.addHeader(name, value);
    }

    @Override
    public WebClientBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (WebClientBuilder) super.addHeaders(headers);
    }

    @Override
    public WebClientBuilder setHeader(CharSequence name, Object value) {
        return (WebClientBuilder) super.setHeader(name, value);
    }

    @Override
    public WebClientBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (WebClientBuilder) super.setHeaders(headers);
    }

    @Override
    public WebClientBuilder auth(BasicToken token) {
        return (WebClientBuilder) super.auth(token);
    }

    @Override
    public WebClientBuilder auth(OAuth1aToken token) {
        return (WebClientBuilder) super.auth(token);
    }

    @Override
    public WebClientBuilder auth(OAuth2Token token) {
        return (WebClientBuilder) super.auth(token);
    }

    @Override
    public WebClientBuilder auth(AuthToken token) {
        return (WebClientBuilder) super.auth(token);
    }

    @Override
    public WebClientBuilder followRedirects() {
        return (WebClientBuilder) super.followRedirects();
    }

    @Override
    public WebClientBuilder followRedirects(RedirectConfig redirectConfig) {
        return (WebClientBuilder) super.followRedirects(redirectConfig);
    }

    @Override
    public WebClientBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        return (WebClientBuilder) super.contextCustomizer(contextCustomizer);
    }
}
