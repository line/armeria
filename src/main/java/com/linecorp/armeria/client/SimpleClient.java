/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

/**
 * A simple {@link Client}.
 */
class SimpleClient implements Client {

    private final ClientCodec codec;
    private final RemoteInvoker invoker;

    /**
     * Creates a new instance with the specified {@link ClientCodec} and {@link RemoteInvoker}.
     */
    SimpleClient(ClientCodec codec, RemoteInvoker invoker) {
        this.codec = requireNonNull(codec, "codec");
        this.invoker = requireNonNull(invoker, "invoker");
    }

    @Override
    public final ClientCodec codec() {
        return codec;
    }

    @Override
    public final RemoteInvoker invoker() {
        return invoker;
    }

    @Override
    public String toString() {
        return "Client(" + codec().getClass().getSimpleName() + ", " + invoker().getClass().getSimpleName() + ')';
    }
}
