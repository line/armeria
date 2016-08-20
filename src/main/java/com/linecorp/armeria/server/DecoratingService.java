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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.function.Function;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * A {@link Service} that decorates another {@link Service}. Do not use this class unless you want to define
 * a new dedicated {@link Service} type by extending this class; prefer {@link Service#decorate(Function)}.
 *
 * @param <T_I> the {@link Request} type of the {@link Service} being decorated
 * @param <T_O> the {@link Response} type of the {@link Service} being decorated
 * @param <R_I> the {@link Request} type of this {@link Service}
 * @param <R_O> the {@link Response} type of this {@link Service}
 */
public abstract class DecoratingService<T_I extends Request, T_O extends Response,
                                        R_I extends Request, R_O extends Response>
        implements Service<R_I, R_O> {

    private final Service<? super T_I, ? extends T_O> delegate;

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    protected DecoratingService(Service<? super T_I, ? extends T_O> delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the {@link Service} being decorated.
     */
    @SuppressWarnings("unchecked")
    protected final <T extends Service<? super T_I, ? extends T_O>> T delegate() {
        return (T) delegate;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        ServiceCallbackInvoker.invokeServiceAdded(cfg, delegate);
    }

    @Override
    public final <T extends Service<?, ?>> Optional<T> as(Class<T> serviceType) {
        final Optional<T> result = Service.super.as(serviceType);
        return result.isPresent() ? result : delegate.as(serviceType);
    }

    @Override
    public String toString() {
        final String simpleName = getClass().getSimpleName();
        final String name = simpleName.isEmpty() ? getClass().getName() : simpleName;
        return name + '(' + delegate + ')';
    }
}
