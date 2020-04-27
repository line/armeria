/*
 * Copyright 2016 LINE Corporation
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

import java.time.Duration;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.function.Supplier;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.RequestId;

/**
 * Creates a new {@link ClientOptions} using the builder pattern.
 *
 * @see ClientBuilder
 */
public final class ClientOptionsBuilder extends AbstractClientOptionsBuilder {

    ClientOptionsBuilder() {}

    ClientOptionsBuilder(ClientOptions options) {
        super(options);
    }

    /**
     * Returns a newly-created {@link ClientOptions} based on the {@link ClientOptionValue}s of this builder.
     */
    public ClientOptions build() {
        return buildOptions();
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public ClientOptionsBuilder options(ClientOptions options) {
        return (ClientOptionsBuilder) super.options(options);
    }

    @Override
    public ClientOptionsBuilder options(ClientOptionValue<?>... options) {
        return (ClientOptionsBuilder) super.options(options);
    }

    @Override
    public ClientOptionsBuilder options(Iterable<ClientOptionValue<?>> options) {
        return (ClientOptionsBuilder) super.options(options);
    }

    @Override
    public <T> ClientOptionsBuilder option(ClientOption<T> option, T value) {
        return (ClientOptionsBuilder) super.option(option, value);
    }

    @Override
    public <T> ClientOptionsBuilder option(ClientOptionValue<T> optionValue) {
        return (ClientOptionsBuilder) super.option(optionValue);
    }

    @Override
    public ClientOptionsBuilder factory(ClientFactory factory) {
        return (ClientOptionsBuilder) super.factory(factory);
    }

    @Override
    public ClientOptionsBuilder writeTimeout(Duration writeTimeout) {
        return (ClientOptionsBuilder) super.writeTimeout(writeTimeout);
    }

    @Override
    public ClientOptionsBuilder writeTimeoutMillis(long writeTimeoutMillis) {
        return (ClientOptionsBuilder) super.writeTimeoutMillis(writeTimeoutMillis);
    }

    @Override
    public ClientOptionsBuilder responseTimeout(Duration responseTimeout) {
        return (ClientOptionsBuilder) super.responseTimeout(responseTimeout);
    }

    @Override
    public ClientOptionsBuilder responseTimeoutMillis(long responseTimeoutMillis) {
        return (ClientOptionsBuilder) super.responseTimeoutMillis(responseTimeoutMillis);
    }

    @Override
    public ClientOptionsBuilder maxResponseLength(long maxResponseLength) {
        return (ClientOptionsBuilder) super.maxResponseLength(maxResponseLength);
    }

    @Override
    public ClientOptionsBuilder requestIdGenerator(Supplier<RequestId> requestIdGenerator) {
        return (ClientOptionsBuilder) super.requestIdGenerator(requestIdGenerator);
    }

    @Override
    public ClientOptionsBuilder endpointRemapper(
            Function<? super Endpoint, ? extends EndpointGroup> endpointRemapper) {
        return (ClientOptionsBuilder) super.endpointRemapper(endpointRemapper);
    }

    @Override
    public ClientOptionsBuilder decorator(
            Function<? super HttpClient, ? extends HttpClient> decorator) {
        return (ClientOptionsBuilder) super.decorator(decorator);
    }

    @Override
    public ClientOptionsBuilder decorator(DecoratingHttpClientFunction decorator) {
        return (ClientOptionsBuilder) super.decorator(decorator);
    }

    @Override
    public ClientOptionsBuilder rpcDecorator(
            Function<? super RpcClient, ? extends RpcClient> decorator) {
        return (ClientOptionsBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public ClientOptionsBuilder rpcDecorator(DecoratingRpcClientFunction decorator) {
        return (ClientOptionsBuilder) super.rpcDecorator(decorator);
    }

    @Override
    public ClientOptionsBuilder addHttpHeader(CharSequence name, Object value) {
        return (ClientOptionsBuilder) super.addHttpHeader(name, value);
    }

    @Override
    public ClientOptionsBuilder addHttpHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> httpHeaders) {
        return (ClientOptionsBuilder) super.addHttpHeaders(httpHeaders);
    }

    @Override
    public ClientOptionsBuilder setHttpHeader(CharSequence name, Object value) {
        return (ClientOptionsBuilder) super.setHttpHeader(name, value);
    }

    @Override
    public ClientOptionsBuilder setHttpHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> httpHeaders) {
        return (ClientOptionsBuilder) super.setHttpHeaders(httpHeaders);
    }
}
