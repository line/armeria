/*
 * Copyright 2015 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * A decorating {@link Service} which implements its {@link #serve(ServiceRequestContext, Request)} method
 * using a given function.
 *
 * @see Service#decorate(DecoratingServiceFunction)
 */
final class FunctionalDecoratingService<I extends Request, O extends Response>
        extends SimpleDecoratingService<I, O> {

    private final DecoratingServiceFunction<I, O> function;

    /**
     * Creates a new instance with the specified function.
     */
    FunctionalDecoratingService(Service<I, O> delegate,
                                DecoratingServiceFunction<I, O> function) {
        super(delegate);
        this.function = requireNonNull(function, "function");
    }

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        return function.serve(delegate(), ctx, req);
    }

    @Override
    public String toString() {
        return FunctionalDecoratingService.class.getSimpleName() + '(' + delegate() + ", " + function + ')';
    }
}
