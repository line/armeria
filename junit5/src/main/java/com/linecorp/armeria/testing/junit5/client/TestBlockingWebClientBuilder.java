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
 * Use the factory methods in {@link TestBlockingWebClient} if you do not have many options to override.
 * Please refer to {@link ClientBuilder} for how decorators and HTTP headers are configured
 */
public final class TestBlockingWebClientBuilder extends AbstractWebClientBuilder {

    /**
     * Creates a new instance.
     */
    TestBlockingWebClientBuilder() {}

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields in
     * {@link SessionProtocol}
     */
    TestBlockingWebClientBuilder(URI uri) {
        super(uri);
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the {@code sessionProtocol} is not one of the fields in
     * {@link SessionProtocol}
     */
    TestBlockingWebClientBuilder(SessionProtocol sessionProtocol, EndpointGroup endpointGroup,
                                 @Nullable String path) {
        super(sessionProtocol, endpointGroup, path);
    }

    /**
     * Returns a newly-created web test client based on the properties of this builder.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     * {@link TestBlockingWebClient#builder(String)} or {@link TestBlockingWebClient#builder(URI)} is not
     * an HTTP scheme
     */
    public TestBlockingWebClient build() {
        return TestBlockingWebClient.of(buildWebClient().blocking());
    }

    // Override the return type of the chaining methods in the superclass.

    @Deprecated
    @Override
    public TestBlockingWebClientBuilder rpcDecorator(
            Function<? super RpcClient, ? extends RpcClient> decorator) {
        return (TestBlockingWebClientBuilder) super.rpcDecorator(decorator);
    }

    @Deprecated
    @Override
    public TestBlockingWebClientBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        return (TestBlockingWebClientBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public TestBlockingWebClientBuilder options(ClientOptions options) {
        return (TestBlockingWebClientBuilder) super.options(options);
    }

    @Override
    public TestBlockingWebClientBuilder options(ClientOptionValue<?>... options) {
        return (TestBlockingWebClientBuilder) super.options(options);
    }

    @Override
    public TestBlockingWebClientBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (TestBlockingWebClientBuilder) super.options(options);
    }

    @Override
    public <T> TestBlockingWebClientBuilder option(ClientOption<T> option, T value) {
        return (TestBlockingWebClientBuilder) super.option(option, value);
    }

    @Override
    public <T> TestBlockingWebClientBuilder option(ClientOptionValue<T> optionValue) {
        return (TestBlockingWebClientBuilder) super.option(optionValue);
    }

    @Override
    public TestBlockingWebClientBuilder factory(ClientFactory factory) {
        return (TestBlockingWebClientBuilder) super.factory(factory);
    }

    @Override
    public TestBlockingWebClientBuilder writeTimeout(Duration writeTimeout) {
        return (TestBlockingWebClientBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public TestBlockingWebClientBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (TestBlockingWebClientBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public TestBlockingWebClientBuilder responseTimeout(Duration responseTimeout) {
        return (TestBlockingWebClientBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public TestBlockingWebClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (TestBlockingWebClientBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public TestBlockingWebClientBuilder maxResponseLength(long maxResponseLength) {
        return (TestBlockingWebClientBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public TestBlockingWebClientBuilder requestAutoAbortDelay(Duration delay) {
        return (TestBlockingWebClientBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public TestBlockingWebClientBuilder requestAutoAbortDelayMillis(long delayMillis) {
        return (TestBlockingWebClientBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public TestBlockingWebClientBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (TestBlockingWebClientBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public TestBlockingWebClientBuilder successFunction(SuccessFunction successFunction) {
        return (TestBlockingWebClientBuilder) super.successFunction(successFunction);
    }

    @Override
    public TestBlockingWebClientBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (TestBlockingWebClientBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public TestBlockingWebClientBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (TestBlockingWebClientBuilder) super.decorator(decorator);
    }

    @Override
    public TestBlockingWebClientBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (TestBlockingWebClientBuilder) super.decorator(decorator);
    }

    @Override
    public TestBlockingWebClientBuilder clearDecorators() {
        return (TestBlockingWebClientBuilder) super.clearDecorators();
    }

    @Override
    public TestBlockingWebClientBuilder addHeader(CharSequence name, Object value) {
        return (TestBlockingWebClientBuilder) super.addHeader(name, value);
    }

    @Override
    public TestBlockingWebClientBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (TestBlockingWebClientBuilder) super.addHeaders(headers);
    }

    @Override
    public TestBlockingWebClientBuilder setHeader(CharSequence name, Object value) {
        return (TestBlockingWebClientBuilder) super.setHeader(name, value);
    }

    @Override
    public TestBlockingWebClientBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (TestBlockingWebClientBuilder) super.setHeaders(headers);
    }

    @Override
    public TestBlockingWebClientBuilder auth(BasicToken token) {
        return (TestBlockingWebClientBuilder) super.auth(token);
    }

    @Override
    public TestBlockingWebClientBuilder auth(OAuth1aToken token) {
        return (TestBlockingWebClientBuilder) super.auth(token);
    }

    @Override
    public TestBlockingWebClientBuilder auth(OAuth2Token token) {
        return (TestBlockingWebClientBuilder) super.auth(token);
    }

    @Override
    public TestBlockingWebClientBuilder auth(AuthToken token) {
        return (TestBlockingWebClientBuilder) super.auth(token);
    }

    @Override
    public TestBlockingWebClientBuilder followRedirects() {
        return (TestBlockingWebClientBuilder) super.followRedirects();
    }

    @Override
    public TestBlockingWebClientBuilder followRedirects(RedirectConfig redirectConfig) {
        return (TestBlockingWebClientBuilder) super.followRedirects(redirectConfig);
    }

    @Override
    public TestBlockingWebClientBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        return (TestBlockingWebClientBuilder) super.contextCustomizer(contextCustomizer);
    }

    @Override
    public TestBlockingWebClientBuilder contextHook(Supplier<? extends AutoCloseable> contextHook) {
        return (TestBlockingWebClientBuilder) super.contextHook(contextHook);
    }
}
