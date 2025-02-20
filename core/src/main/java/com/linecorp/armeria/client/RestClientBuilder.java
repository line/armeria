/*
 * Copyright 2022 LINE Corporation
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
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.auth.AuthToken;
import com.linecorp.armeria.common.auth.BasicToken;
import com.linecorp.armeria.common.auth.OAuth1aToken;
import com.linecorp.armeria.common.auth.OAuth2Token;

/**
 * Creates a new <a href="https://restfulapi.net/">REST</a> client that connects to the specified {@link URI}
 * using the builder pattern. This client is designed to easily exchange RESTful APIs.
 *
 * <p>Use the factory methods in {@link RestClient} if you do not have many options to
 * override. Please refer to {@link ClientBuilder} for how decorators and HTTP headers are configured.
 */
@UnstableApi
public final class RestClientBuilder extends AbstractWebClientBuilder {

    /**
     * Creates a new instance.
     */
    RestClientBuilder() {}

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the scheme of the uri is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    RestClientBuilder(URI uri) {
        super(uri);
    }

    /**
     * Creates a new instance.
     *
     * @throws IllegalArgumentException if the {@code sessionProtocol} is not one of the fields
     *                                  in {@link SessionProtocol}
     */
    RestClientBuilder(SessionProtocol sessionProtocol, EndpointGroup endpointGroup, @Nullable String path) {
        super(sessionProtocol, endpointGroup, path);
    }

    /**
     * Returns a newly-created web client based on the properties of this builder.
     *
     * @throws IllegalArgumentException if the scheme of the {@code uri} specified in
     *                                  {@link RestClient#builder(String)} or
     *                                  {@link RestClient#builder(URI)} is not an HTTP scheme
     */
    public RestClient build() {
        return new DefaultRestClient(buildWebClient());
    }

    // Override the return type of the chaining methods in the superclass.

    @Deprecated
    @Override
    public RestClientBuilder rpcDecorator(Function<? super RpcClient, ? extends RpcClient> decorator) {
        return (RestClientBuilder) super.rpcDecorator(decorator);
    }

    @Deprecated
    @Override
    public RestClientBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        return (RestClientBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public RestClientBuilder options(ClientOptions options) {
        return (RestClientBuilder) super.options(options);
    }

    @Override
    public RestClientBuilder options(ClientOptionValue<?>... options) {
        return (RestClientBuilder) super.options(options);
    }

    @Override
    public RestClientBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (RestClientBuilder) super.options(options);
    }

    @Override
    public <T> RestClientBuilder option(ClientOption<T> option, T value) {
        return (RestClientBuilder) super.option(option, value);
    }

    @Override
    public <T> RestClientBuilder option(ClientOptionValue<T> optionValue) {
        return (RestClientBuilder) super.option(optionValue);
    }

    @Override
    public RestClientBuilder factory(ClientFactory factory) {
        return (RestClientBuilder) super.factory(factory);
    }

    @Override
    public RestClientBuilder writeTimeout(Duration writeTimeout) {
        return (RestClientBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public RestClientBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (RestClientBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public RestClientBuilder responseTimeout(Duration responseTimeout) {
        return (RestClientBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public RestClientBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (RestClientBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public RestClientBuilder maxResponseLength(long maxResponseLength) {
        return (RestClientBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public RestClientBuilder requestAutoAbortDelay(Duration delay) {
        return (RestClientBuilder) super.requestAutoAbortDelay(delay);
    }

    @Override
    public RestClientBuilder requestAutoAbortDelayMillis(long delayMillis) {
        return (RestClientBuilder) super.requestAutoAbortDelayMillis(delayMillis);
    }

    @Override
    public RestClientBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (RestClientBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public RestClientBuilder successFunction(SuccessFunction successFunction) {
        return (RestClientBuilder) super.successFunction(successFunction);
    }

    @Override
    public RestClientBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (RestClientBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public RestClientBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (RestClientBuilder) super.decorator(decorator);
    }

    @Override
    public RestClientBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (RestClientBuilder) super.decorator(decorator);
    }

    @Override
    public RestClientBuilder clearDecorators() {
        return (RestClientBuilder) super.clearDecorators();
    }

    @Override
    public RestClientBuilder addHeader(CharSequence name, Object value) {
        return (RestClientBuilder) super.addHeader(name, value);
    }

    @Override
    public RestClientBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (RestClientBuilder) super.addHeaders(headers);
    }

    @Override
    public RestClientBuilder setHeader(CharSequence name, Object value) {
        return (RestClientBuilder) super.setHeader(name, value);
    }

    @Override
    public RestClientBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        return (RestClientBuilder) super.setHeaders(headers);
    }

    @Override
    public RestClientBuilder auth(BasicToken token) {
        return (RestClientBuilder) super.auth(token);
    }

    @Override
    public RestClientBuilder auth(OAuth1aToken token) {
        return (RestClientBuilder) super.auth(token);
    }

    @Override
    public RestClientBuilder auth(OAuth2Token token) {
        return (RestClientBuilder) super.auth(token);
    }

    @Override
    public RestClientBuilder auth(AuthToken token) {
        return (RestClientBuilder) super.auth(token);
    }

    @Override
    public RestClientBuilder followRedirects() {
        return (RestClientBuilder) super.followRedirects();
    }

    @Override
    public RestClientBuilder followRedirects(RedirectConfig redirectConfig) {
        return (RestClientBuilder) super.followRedirects(redirectConfig);
    }

    @Override
    public RestClientBuilder contextCustomizer(
            Consumer<? super ClientRequestContext> contextCustomizer) {
        return (RestClientBuilder) super.contextCustomizer(contextCustomizer);
    }

    @Override
    public RestClientBuilder contextHook(Supplier<? extends AutoCloseable> contextHook) {
        return (RestClientBuilder) super.contextHook(contextHook);
    }

    @Override
    public RestClientBuilder responseTimeoutMode(ResponseTimeoutMode responseTimeoutMode) {
        return (RestClientBuilder) super.responseTimeoutMode(responseTimeoutMode);
    }

    @Override
    public RestClientBuilder preprocessor(HttpPreprocessor decorator) {
        return (RestClientBuilder) super.preprocessor(decorator);
    }

    @Override
    @Deprecated
    public RestClientBuilder rpcPreprocessor(RpcPreprocessor rpcPreprocessor) {
        return (RestClientBuilder) super.rpcPreprocessor(rpcPreprocessor);
    }
}
