/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server;

import java.util.function.Function;

import com.linecorp.armeria.internal.server.TransientServiceOptionsBuilder;

/**
 * Builds a {@link WrappingTransientRpcService}.
 */
public final class WrappingTransientRpcServiceBuilder implements TransientServiceBuilder {

    private final TransientServiceOptionsBuilder
            transientServiceOptionsBuilder = new TransientServiceOptionsBuilder();

    WrappingTransientRpcServiceBuilder() {}

    @Override
    public WrappingTransientRpcServiceBuilder transientServiceOptions(
            TransientServiceOption... transientServiceOptions) {
        transientServiceOptionsBuilder.transientServiceOptions(transientServiceOptions);
        return this;
    }

    @Override
    public WrappingTransientRpcServiceBuilder transientServiceOptions(
            Iterable<TransientServiceOption> transientServiceOptions) {
        transientServiceOptionsBuilder.transientServiceOptions(transientServiceOptions);
        return this;
    }

    /**
     * Returns a new {@link WrappingTransientRpcService} based on the properties of this builder.
     */
    public WrappingTransientRpcService build(RpcService delegate) {
        return new WrappingTransientRpcService(delegate, transientServiceOptionsBuilder.build());
    }

    /**
     * Creates a new {@link WrappingTransientRpcService} decorator based on the properties of this builder.
     */
    public Function<? super RpcService, WrappingTransientRpcService> newDecorator() {
        return this::build;
    }
}
