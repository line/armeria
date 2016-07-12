/*
 * Copyright 2015 LINE Corporation
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

import java.util.function.BiFunction;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * A {@link Service} which uses a {@link BiFunction} as its implementation.
 *
 * @see Service#of(BiFunction)
 */
final class BiFunctionService<I extends Request, O extends Response> implements Service<I, O> {

    private final BiFunction<? super ServiceRequestContext, ? super I, ? extends O> function;

    /**
     * Creates a new instance with the specified function.
     */
    BiFunctionService(BiFunction<? super ServiceRequestContext, ? super I, ? extends O> function) {
        requireNonNull(function, "function");
        this.function = function;
    }

    @Override
    public O serve(ServiceRequestContext ctx, I req) throws Exception {
        return function.apply(ctx, req);
    }

    @Override
    public String toString() {
        return "BiFunctionService(" + function + ')';
    }
}
