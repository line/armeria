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

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An HTTP/2 {@link Service}.
 */
@FunctionalInterface
public interface HttpService extends Service<HttpRequest, HttpResponse> {

    @Override
    @CheckReturnValue
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

        return newService;
    }

    /**
     * Creates a new {@link HttpService} that decorates this {@link HttpService} with the specified
     * {@link DecoratingHttpServiceFunction}.
     */
    default HttpService decorate(DecoratingHttpServiceFunction function) {
        return new FunctionalDecoratingHttpService(this, function);
    }

    /**
     * Determines an {@link ExchangeType} for this {@link HttpService} from the given {@link RoutingContext}.
     * By default, {@link ExchangeType#BIDI_STREAMING} is set.
     *
     * <p>Note that an {@link HttpRequest} will be aggregated before serving the {@link HttpService} if
     * {@link ExchangeType#UNARY} or {@link ExchangeType#RESPONSE_STREAMING} is set.
     */
    @UnstableApi
    default ExchangeType exchangeType(RoutingContext routingContext) {
        return ExchangeType.BIDI_STREAMING;
    }

    /**
     * Returns the {@link ServiceOptions} of this {@link HttpService}.
     */
    @UnstableApi
    default ServiceOptions options() {
        return ServiceOptions.of();
    }
}
