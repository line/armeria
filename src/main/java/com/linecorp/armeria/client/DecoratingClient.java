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

import java.util.function.Function;

/**
 * A {@link Client} that decorates another {@link Client}.
 */
public class DecoratingClient extends SimpleClient {

    /**
     * Creates a new instance that decorates the specified {@link Client} and its {@link ClientCodec} and
     * {@link RemoteInvoker} using the specified {@code codecDecorator} and {@code invokerDecorator}.
     */
    protected <T extends ClientCodec, U extends ClientCodec, V extends RemoteInvoker, W extends RemoteInvoker>
    DecoratingClient(Client client, Function<T, U> codecDecorator, Function<V, W> invokerDecorator) {
        super(decorateCodec(client, codecDecorator), decorateInvoker(client, invokerDecorator));
    }

    private static <T extends ClientCodec, U extends ClientCodec>
    ClientCodec decorateCodec(Client client, Function<T, U> codecDecorator) {

        requireNonNull(codecDecorator, "codecDecorator");

        @SuppressWarnings("unchecked")
        final T codec = (T) client.codec();
        final U decoratedCodec = codecDecorator.apply(codec);

        return decoratedCodec != null ? decoratedCodec : codec;
    }

    private static <T extends RemoteInvoker, U extends RemoteInvoker>
    RemoteInvoker decorateInvoker(Client client, Function<T, U> invokerDecorator) {

        requireNonNull(invokerDecorator, "invokerDecorator");

        @SuppressWarnings("unchecked")
        final T invoker = (T) client.invoker();
        final U decoratedInvoker = invokerDecorator.apply(invoker);

        return decoratedInvoker != null ? decoratedInvoker : invoker;
    }
}
