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

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A set of {@link Function}s that transforms a {@link HttpPreprocessor} or
 * {@link RpcPreprocessor} into another.
 */
@UnstableApi
public final class ClientPreprocessors {

    private static final ClientPreprocessors NONE =
            new ClientPreprocessors(ImmutableList.of(), ImmutableList.of());

    /**
     * Returns an empty {@link ClientDecoration} which does not decorate a {@link Client}.
     */
    public static ClientPreprocessors of() {
        return NONE;
    }

    /**
     * Creates a new instance from a single {@link HttpPreprocessor}.
     *
     * @param preprocessor the {@link HttpPreprocessor} that transforms an
     *                     {@link HttpPreClient} to another
     */
    public static ClientPreprocessors of(HttpPreprocessor preprocessor) {
        return builder().add(preprocessor).build();
    }

    /**
     * Creates a new instance from a single {@link RpcPreprocessor}.
     *
     * @param preprocessor the {@link RpcPreprocessor} that transforms an {@link RpcPreClient}
     *                     to another
     */
    public static ClientPreprocessors ofRpc(RpcPreprocessor preprocessor) {
        return builder().addRpc(preprocessor).build();
    }

    /**
     * Returns a newly created {@link ClientPreprocessorsBuilder}.
     */
    public static ClientPreprocessorsBuilder builder() {
        return new ClientPreprocessorsBuilder();
    }

    private final List<HttpPreprocessor> preprocessors;
    private final List<RpcPreprocessor> rpcPreprocessors;

    ClientPreprocessors(List<HttpPreprocessor> preprocessors, List<RpcPreprocessor> rpcPreprocessors) {
        this.preprocessors = ImmutableList.copyOf(preprocessors);
        this.rpcPreprocessors = ImmutableList.copyOf(rpcPreprocessors);
    }

    /**
     * Returns the HTTP-level preprocessors.
     */
    public List<HttpPreprocessor> preprocessors() {
        return preprocessors;
    }

    /**
     * Returns the RPC-level preprocessors.
     */
    public List<RpcPreprocessor> rpcPreprocessors() {
        return rpcPreprocessors;
    }

    /**
     * Decorates the specified {@link HttpPreClient} using preprocessors.
     *
     * @param execution the {@link HttpPreClient} being decorated
     */
    public HttpPreClient decorate(HttpPreClient execution) {
        for (HttpPreprocessor preprocessor : preprocessors) {
            final HttpPreClient execution0 = execution;
            execution = (ctx, req) -> preprocessor.execute(execution0, ctx, req);
        }
        return execution;
    }

    /**
     * Decorates the specified {@link RpcPreClient} using preprocessors.
     *
     * @param execution the {@link RpcPreClient} being decorated
     */
    public RpcPreClient rpcDecorate(RpcPreClient execution) {
        for (RpcPreprocessor rpcPreprocessor : rpcPreprocessors) {
            final RpcPreClient execution0 = execution;
            execution = (ctx, req) -> rpcPreprocessor.execute(execution0, ctx, req);
        }
        return execution;
    }

    boolean isEmpty() {
        return preprocessors.isEmpty() && rpcPreprocessors.isEmpty();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final ClientPreprocessors that = (ClientPreprocessors) object;
        return Objects.equals(preprocessors, that.preprocessors) &&
               Objects.equals(rpcPreprocessors, that.rpcPreprocessors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(preprocessors, rpcPreprocessors);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("preprocessors", preprocessors)
                          .add("rpcPreprocessors", rpcPreprocessors)
                          .toString();
    }
}
