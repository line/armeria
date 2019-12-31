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

package com.linecorp.armeria.server.composition;

import java.util.List;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Routed;
import com.linecorp.armeria.server.RoutingContext;

/**
 * A general purpose {@link AbstractCompositeService} implementation. Useful when you do not want to define
 * a new dedicated {@link HttpService} type.
 */
public final class SimpleCompositeService
        extends AbstractCompositeService<HttpService, HttpRequest, HttpResponse> implements HttpService {

    /**
     * Returns a new {@link SimpleCompositeService} that is composed of the specified entries.
     */
    @SafeVarargs
    public static SimpleCompositeService of(CompositeServiceEntry<HttpService>... services) {
        return new SimpleCompositeService(services);
    }

    /**
     * Returns a new {@link SimpleCompositeService} that is composed of the specified entries.
     */
    public static SimpleCompositeService of(Iterable<CompositeServiceEntry<HttpService>> services) {
        return new SimpleCompositeService(services);
    }

    /**
     * Returns a new {@link SimpleCompositeServiceBuilder}.
     */
    public static SimpleCompositeServiceBuilder builder() {
        return new SimpleCompositeServiceBuilder();
    }

    /**
     * Creates a new instance that is composed of the specified entries.
     *
     * @deprecated Use {@link #of(CompositeServiceEntry[])}.
     */
    @SafeVarargs
    @Deprecated
    public SimpleCompositeService(CompositeServiceEntry<HttpService>... services) {
        super(services);
    }

    /**
     * Creates a new instance that is composed of the specified entries.
     *
     * @deprecated Use {@link #of(Iterable)}.
     */
    @Deprecated
    public SimpleCompositeService(Iterable<CompositeServiceEntry<HttpService>> services) {
        super(services);
    }

    @Override
    public List<CompositeServiceEntry<HttpService>> services() {
        return super.services();
    }

    @Override
    public HttpService serviceAt(int index) {
        return super.serviceAt(index);
    }

    @Override
    public Routed<HttpService> findService(RoutingContext routingCtx) {
        return super.findService(routingCtx);
    }
}
