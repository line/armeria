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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractUnwrappable;

/**
 * A {@link Service} that decorates another {@link Service}. Use {@link SimpleDecoratingHttpService} or
 * {@link SimpleDecoratingRpcService} if your {@link Service} has the same {@link Request} and {@link Response}
 * type with the {@link Service} being decorated.
 *
 * @param <T_I> the {@link Request} type of the {@link Service} being decorated
 * @param <T_O> the {@link Response} type of the {@link Service} being decorated
 * @param <R_I> the {@link Request} type of this {@link Service}
 * @param <R_O> the {@link Response} type of this {@link Service}
 */
public abstract class DecoratingService<T_I extends Request, T_O extends Response,
                                        R_I extends Request, R_O extends Response>
        extends AbstractUnwrappable<Service<T_I, T_O>>
        implements Service<R_I, R_O> {

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    protected DecoratingService(Service<T_I, T_O> delegate) {
        super(delegate);
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        ServiceCallbackInvoker.invokeServiceAdded(cfg, unwrap());
    }

    @Override
    public boolean shouldCachePath(String path, @Nullable String query, Route route) {
        return unwrap().shouldCachePath(path, query, route);
    }
}
