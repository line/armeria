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

package com.linecorp.armeria.testing.junit5.client;

import java.net.URI;
import java.time.Duration;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.AbstractWebClientBuilder;
import com.linecorp.armeria.client.ClientBuilder;
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
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;

/**
 * Creates a new web client that connects to the specified {@link URI} using the builder pattern.
 * Use the factory methods in {@link WebTestClient} if you do not have many options to override.
 * Please refer to {@link ClientBuilder} for how decorators and HTTP headers are configured
 */
public final class WebTestClientBuilder extends AbstractWebClientBuilder {

    /**
     * Creates a new instance.
     */
    WebTestClientBuilder() {}

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    WebTestClientBuilder(URI uri) {
        super(uri);
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the {@code sessionProtocol} is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    WebTestClientBuilder(SessionProtocol sessionProtocol, EndpointGroup endpointGroup, @Nullable String path) {
        super(sessionProtocol, endpointGroup, path);
    }

    /**
     * Returns a newly-created web test client based on the properties of this builder.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link WebTestClient#builder(String)} or
     *                                  {@link WebTestClient#builder(URI)} is not an HTTP scheme
     */
    public WebTestClient build() {
        return WebTestClient.of(buildWebClient().blocking());
    }

    // Override the return type of the chaining methods in the superclass.

    @Deprecated
    @Override
    public WebTestClientBuilder rpcDecorator(Function<? super RpcClient, ? extends RpcClient> decorator) {
        return (WebTestClientBuilder) super.rpcDecorator(decorator);
    }

    @Deprecated
    @Override
    public WebTestClientBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        return (WebTestClientBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public WebTestClientBuilder options(ClientOptions options) {
        return (WebTestClientBuilder) super.options(options);
    }

    @Override
    public WebTestClientBuilder options(ClientOptionValue<?>... options) {
        return (WebTestClientBuilder) super.options(options);
    }

    @Override
    public WebTestClientBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (WebTestClientBuilder) super.options(options);
    }

    @Override
    public <T> WebTestClientBuilder option(ClientOption<T> option, T value) {
        return (WebTestClientBuilder) super.option(option, value);
    }

    @Override
    public <T> WebTestClientBuilder option(ClientOptionValue<T> optionValue) {
        return (WebTestClientBuilder) super.option(optionValue);
    }

    @Override
    public WebTestClientBuilder factory(ClientFactory factory) {
        return (WebTestClientBuilder) super.factory(factory);
    }

    @Override
    public WebTestClientBuilder writeTimeout(Duration writeTimeout) {
        return (WebTestClientBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public WebTestClientBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (WebTestClientBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public WebTestClientBuilder responseTimeout(Duration responseTimeout) {
        return (WebTestClientBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public WebTestClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (WebTestClientBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public WebTestClientBuilder maxResponseLength(long maxResponseLength) {
        return (WebTestClientBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public WebTestClientBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (WebTestClientBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public WebTestClientBuilder successFunction(SuccessFunction successFunction) {
        return (WebTestClientBuilder) super.successFunction(successFunction);
    }

    @Override
    public WebTestClientBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (WebTestClientBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public WebTestClientBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (WebTestClientBuilder) super.decorator(decorator);
    }

    @Override
    public WebTestClientBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (WebTestClientBuilder) super.decorator(decorator);
    }

    @Override
    public WebTestClientBuilder clearDecorators() {
        return (WebTestClientBuilder) super.clearDecorators();
    }

    @Override
    public WebTestClientBuilder addHeader(CharSequence name, Object value) {
        return (WebTestClientBuilder) super.addHeader(name, value);
    }

    @Override
    public WebTestClientBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (WebTestClientBuilder) super.addHeaders(headers);
    }

    @Override
    public WebTestClientBuilder setHeader(CharSequence name, Object value) {
        return (WebTestClientBuilder) super.setHeader(name, value);
    }

    @Override
    public WebTestClientBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (WebTestClientBuilder) super.setHeaders(headers);
    }

    @Override
    public WebTestClientBuilder auth(BasicToken token) {
        return (WebTestClientBuilder) super.auth(token);
    }

    @Override
    public WebTestClientBuilder auth(OAuth1aToken token) {
        return (WebTestClientBuilder) super.auth(token);
    }

    @Override
    public WebTestClientBuilder auth(OAuth2Token token) {
        return (WebTestClientBuilder) super.auth(token);
    }

    @Override
    public WebTestClientBuilder auth(AuthToken token) {
        return (WebTestClientBuilder) super.auth(token);
    }

    @Override
    public WebTestClientBuilder followRedirects() {
        return (WebTestClientBuilder) super.followRedirects();
    }

    @Override
    public WebTestClientBuilder followRedirects(RedirectConfig redirectConfig) {
        return (WebTestClientBuilder) super.followRedirects(redirectConfig);
    }

    @Override
    public WebTestClientBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        return (WebTestClientBuilder) super.contextCustomizer(contextCustomizer);
    }
}
