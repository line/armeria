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

import java.util.function.Function;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.internal.server.DecoratingServiceUtil;

/**
 * An HTTP/2 {@link Service}.
 */
@FunctionalInterface
public interface HttpService extends Service<HttpRequest, HttpResponse> {

    @Override
    HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception;

    /**
     * Creates a new {@link Service} that decorates this {@link HttpService} with the specified
     * {@code decorator}.
     */
    default <R extends Service<R_I, R_O>, R_I extends Request, R_O extends Response>
    R decorate(Function<? super HttpService, R> decorator) {
        final R newService = decorator.apply(this);

        if (newService == null) {
            throw new NullPointerException("decorator.apply() returned null: " + decorator);
        }
        DecoratingServiceUtil.validateDecorator(newService);

        return newService;
    }

    /**
     * Creates a new {@link HttpService} that decorates this {@link HttpService} with the specified
     * {@link DecoratingHttpServiceFunction}.
     */
    default HttpService decorate(DecoratingHttpServiceFunction function) {
        return new FunctionalDecoratingHttpService(this, function);
    }
}
