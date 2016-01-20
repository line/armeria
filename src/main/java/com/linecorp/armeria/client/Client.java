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

import java.util.function.Function;

/**
 * A set of components required for invoking a remote service.
 */
public interface Client {

    /**
     * Creates a new function that decorates a {@link Client} with the specified {@code codecDecorator}
     * and {@code invokerDecorator}.
     * <p>
     * This factory method is useful when you want to write a reusable factory method that returns
     * a decorator function and the returned decorator function is expected to be consumed by
     * {@link #decorate(Function)}. For example, this may be a factory which combines multiple decorators,
     * any of which could be decorating a codec and/or a handler.
     * </p><p>
     * Consider using {@link #decorateCodec(Function)} or {@link #decorateInvoker(Function)}
     * instead for simplicity unless you are writing a factory method of a decorator function.
     * </p><p>
     * If you need a function that decorates only a codec or an invoker, use {@link Function#identity()} for
     * the non-decorated property. e.g:
     * </p><pre>{@code
     * newDecorator(Function.identity(), (handler) -> { ... });
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    static <T extends ClientCodec, U extends ClientCodec,
            V extends RemoteInvoker, W extends RemoteInvoker>
    Function<Client, Client> newDecorator(Function<T, U> codecDecorator, Function<V, W> invokerDecorator) {
        return client -> new DecoratingClient(client, codecDecorator, invokerDecorator);
    }

    /**
     * Returns the {@link ClientCodec}.
     */
    ClientCodec codec();

    /**
     * Returns the {@link RemoteInvoker}.
     */
    RemoteInvoker invoker();

    /**
     * Creates a new {@link Client} that decorates this {@link Client} with the specified {@code decorator}.
     */
    default Client decorate(Function<Client, Client> decorator) {
        @SuppressWarnings("unchecked")
        final Client newClient = decorator.apply(this);

        if (newClient != null) {
            return newClient;
        } else {
            return this;
        }
    }

    /**
     * Creates a new {@link Client} that decorates the {@link ClientCodec} of this {@link Client} with the
     * specified {@code codecDecorator}.
     */
    default <T extends ClientCodec, U extends ClientCodec>
    Client decorateCodec(Function<T, U> codecDecorator) {
        return new DecoratingClient(this, codecDecorator, Function.identity());
    }

    /**
     * Creates a new {@link Client} that decorates the {@link RemoteInvoker} of this
     * {@link Client} with the specified {@code invokerDecorator}.
     */
    default <T extends RemoteInvoker, U extends RemoteInvoker>
    Client decorateInvoker(Function<T, U> invokerDecorator) {
        return new DecoratingClient(this, Function.identity(), invokerDecorator);
    }
}
