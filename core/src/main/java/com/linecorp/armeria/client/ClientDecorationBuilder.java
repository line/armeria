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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientDecoration.Entry;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * Creates a new {@link ClientDecoration} using the builder pattern.
 */
public final class ClientDecorationBuilder {

    private final List<Entry<?, ?>> entries = new ArrayList<>();

    /**
     * Adds a new decorator {@link Function}.
     *
     * @param requestType the type of the {@link Request} that the {@code decorator} is interested in
     * @param responseType the type of the {@link Response} that the {@code decorator} is interested in
     * @param decorator the {@link Function} that transforms a {@link Client} to another
     * @param <T> the type of the {@link Client} being decorated
     * @param <R> the type of the {@link Client} produced by the {@code decorator}
     * @param <I> the {@link Request} type of the {@link Client} being decorated
     * @param <O> the {@link Response} type of the {@link Client} being decorated
     */
    public <T extends Client<I, O>, R extends Client<I, O>, I extends Request, O extends Response>
    ClientDecorationBuilder add(Class<I> requestType, Class<O> responseType, Function<T, R> decorator) {

        requireNonNull(requestType, "requestType");
        requireNonNull(responseType, "responseType");
        requireNonNull(decorator, "decorator");

        if (!(requestType == HttpRequest.class && responseType == HttpResponse.class ||
              requestType == RpcRequest.class && responseType == RpcResponse.class)) {
            throw new IllegalArgumentException(
                    "requestType and responseType must be HttpRequest and HttpResponse or " +
                    "RpcRequest and RpcResponse: " + requestType.getName() + " and " + responseType.getName());
        }

        entries.add(new Entry<>(requestType, responseType, decorator));
        return this;
    }

    /**
     * Adds a new {@link DecoratingClientFunction}.
     *
     * @param requestType the type of the {@link Request} that the {@code decorator} is interested in
     * @param responseType the type of the {@link Response} that the {@code decorator} is interested in
     * @param decorator the {@link DecoratingClientFunction} that intercepts an invocation
     * @param <I> the {@link Request} type of the {@link Client} being decorated
     * @param <O> the {@link Response} type of the {@link Client} being decorated
     */
    public <I extends Request, O extends Response> ClientDecorationBuilder add(
            Class<I> requestType, Class<O> responseType, DecoratingClientFunction<I, O> decorator) {

        requireNonNull(requestType, "requestType");
        requireNonNull(responseType, "responseType");
        requireNonNull(decorator, "decorator");

        entries.add(new Entry<>(requestType, responseType,
                                delegate -> new FunctionalDecoratingClient<>(delegate, decorator)));
        return this;
    }

    /**
     * Returns a newly-created {@link ClientDecoration} based on the decorators added to this builder.
     */
    public ClientDecoration build() {
        return new ClientDecoration(entries);
    }
}
