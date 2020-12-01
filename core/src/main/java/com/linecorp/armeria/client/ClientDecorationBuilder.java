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

/**
 * Creates a new {@link ClientDecoration} using the builder pattern.
 */
public final class ClientDecorationBuilder {

    private final List<Function<? super HttpClient, ? extends HttpClient>> decorators = new ArrayList<>();
    private final List<Function<? super RpcClient, ? extends RpcClient>> rpcDecorators = new ArrayList<>();

    ClientDecorationBuilder() {}

    /**
     * Adds the specified {@link ClientDecoration}.
     */
    public ClientDecorationBuilder add(ClientDecoration clientDecoration) {
        requireNonNull(clientDecoration, "clientDecoration");
        clientDecoration.decorators().forEach(this::add);
        clientDecoration.rpcDecorators().forEach(this::addRpc);
        return this;
    }

    /**
     * Adds the specified HTTP-level {@code decorator}.
     *
     * @param decorator the {@link Function} that transforms an {@link HttpClient} to another
     */
    public ClientDecorationBuilder add(Function<? super HttpClient, ? extends HttpClient> decorator) {
        decorators.add(requireNonNull(decorator, "decorator"));
        return this;
    }

    /**
     * Adds the specified HTTP-level {@code decorator}.
     *
     * @param decorator the {@link DecoratingHttpClientFunction} that intercepts an invocation
     */
    public ClientDecorationBuilder add(DecoratingHttpClientFunction decorator) {
        requireNonNull(decorator, "decorator");
        return add(delegate -> new FunctionalDecoratingHttpClient(delegate, decorator));
    }

    /**
     * Clears all HTTP-level and RPC-level decorators set so far.
     */
    public ClientDecorationBuilder clear() {
        decorators.clear();
        rpcDecorators.clear();
        return this;
    }

    /**
     * Adds the specified RPC-level {@code decorator}.
     *
     * @param decorator the {@link Function} that transforms an {@link RpcClient} to another
     */
    public ClientDecorationBuilder addRpc(Function<? super RpcClient, ? extends RpcClient> decorator) {
        rpcDecorators.add(requireNonNull(decorator, "decorator"));
        return this;
    }

    /**
     * Adds the specified RPC-level {@code decorator}.
     *
     * @param decorator the {@link DecoratingHttpClientFunction} that intercepts an invocation
     */
    public ClientDecorationBuilder addRpc(DecoratingRpcClientFunction decorator) {
        requireNonNull(decorator, "decorator");
        return addRpc(delegate -> new FunctionalDecoratingRpcClient(delegate, decorator));
    }

    /**
     * Returns a newly-created {@link ClientDecoration} based on the decorators added to this builder.
     */
    public ClientDecoration build() {
        return new ClientDecoration(decorators, rpcDecorators);
    }
}
