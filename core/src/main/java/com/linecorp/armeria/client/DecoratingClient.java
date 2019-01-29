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

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * Decorates a {@link Client}. Use {@link SimpleDecoratingClient},
 * {@link ClientBuilder#decorator(DecoratingClientFunction)} or
 * {@link ClientBuilder#rpcDecorator(DecoratingClientFunction)} if your {@link Client} has the same
 * {@link Request} and {@link Response} type with the {@link Client} being decorated.
 *
 * @param <T_I> the {@link Request} type of the {@link Client} being decorated
 * @param <T_O> the {@link Response} type of the {@link Client} being decorated
 * @param <R_I> the {@link Request} type of this {@link Client}
 * @param <R_O> the {@link Response} type of this {@link Client}
 */
public abstract class DecoratingClient<T_I extends Request, T_O extends Response,
                                       R_I extends Request, R_O extends Response> implements Client<R_I, R_O> {

    private final Client<T_I, T_O> delegate;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    protected DecoratingClient(Client<T_I, T_O> delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the {@link Client} being decorated.
     */
    @SuppressWarnings("unchecked")
    protected final <T extends Client<T_I, T_O>> T delegate() {
        return (T) delegate;
    }

    @Override
    public String toString() {
        final String simpleName = getClass().getSimpleName();
        final String name = simpleName.isEmpty() ? getClass().getName() : simpleName;
        return name + '(' + delegate + ')';
    }
}
