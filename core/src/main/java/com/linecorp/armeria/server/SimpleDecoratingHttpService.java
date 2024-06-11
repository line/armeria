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

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;

/**
 * An {@link HttpService} that decorates another {@link HttpService}.
 *
 * @see HttpService#decorate(DecoratingHttpServiceFunction)
 */
public abstract class SimpleDecoratingHttpService extends SimpleDecoratingService<HttpRequest, HttpResponse>
        implements HttpService {

    /**
     * Creates a new instance that decorates the specified {@link HttpService}.
     */
    protected SimpleDecoratingHttpService(HttpService delegate) {
        super(delegate);
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        return ((HttpService) unwrap()).exchangeType(routingContext);
    }

    @Override
    public ServiceOptions options() {
        return ((HttpService) unwrap()).options();
    }
}
