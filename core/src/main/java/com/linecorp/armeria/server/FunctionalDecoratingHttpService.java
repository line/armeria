/*
 * Copyright 2019 LINE Corporation
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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * A decorating {@link HttpService} which implements its {@link #serve(ServiceRequestContext, HttpRequest)}
 * method using a given function.
 *
 * @see HttpService#decorate(DecoratingHttpServiceFunction)
 */
final class FunctionalDecoratingHttpService extends SimpleDecoratingHttpService {

    private final DecoratingHttpServiceFunction function;

    /**
     * Creates a new instance with the specified function.
     */
    FunctionalDecoratingHttpService(HttpService delegate,
                                    DecoratingHttpServiceFunction function) {
        super(delegate);
        this.function = requireNonNull(function, "function");
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return function.serve((HttpService) unwrap(), ctx, req);
    }

    @Override
    public String toString() {
        return FunctionalDecoratingHttpService.class.getSimpleName() + '(' + unwrap() + ", " + function + ')';
    }
}
