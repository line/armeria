/*
 * Copyright 2024 LINE Corporation
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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Creates a new {@link ClientPreprocessors} using the builder pattern.
 */
@UnstableApi
public final class ClientPreprocessorsBuilder {

    private final List<HttpPreprocessor> preprocessors = new ArrayList<>();
    private final List<RpcPreprocessor> rpcPreprocessors = new ArrayList<>();

    /**
     * Adds the specified {@link ClientPreprocessors}.
     */
    public ClientPreprocessorsBuilder add(ClientPreprocessors preprocessors) {
        requireNonNull(preprocessors, "preprocessors");
        preprocessors.preprocessors().forEach(this::add);
        preprocessors.rpcPreprocessors().forEach(this::addRpc);
        return this;
    }

    /**
     * Adds the specified HTTP-level {@code preprocessor}.
     *
     * @param preprocessor the {@link HttpPreprocessor} that preprocesses an invocation
     */
    public ClientPreprocessorsBuilder add(HttpPreprocessor preprocessor) {
        preprocessors.add(requireNonNull(preprocessor, "preprocessor"));
        return this;
    }

    /**
     * Adds the specified RPC-level {@code preprocessor}.
     *
     * @param rpcPreprocessor the {@link HttpPreprocessor} that preprocesses an invocation
     */
    public ClientPreprocessorsBuilder addRpc(RpcPreprocessor rpcPreprocessor) {
        rpcPreprocessors.add(requireNonNull(rpcPreprocessor, "rpcPreprocessor"));
        return this;
    }

    /**
     * Returns a newly-created {@link ClientPreprocessors} based on the decorators added to this builder.
     */
    public ClientPreprocessors build() {
        return new ClientPreprocessors(preprocessors, rpcPreprocessors);
    }
}
