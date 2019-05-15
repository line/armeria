/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.internal.annotation;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Service;

/**
 * Details of an annotated HTTP service method.
 */
public final class AnnotatedHttpServiceElement {

    private final Route route;

    private final AnnotatedHttpService service;

    private final Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> decorator;

    AnnotatedHttpServiceElement(Route route,
                                AnnotatedHttpService service,
                                Function<Service<HttpRequest, HttpResponse>,
                                        ? extends Service<HttpRequest, HttpResponse>> decorator) {
        this.route = requireNonNull(route, "route");
        this.service = requireNonNull(service, "service");
        this.decorator = requireNonNull(decorator, "decorator");
    }

    /**
     * Returns the {@link Route}.
     */
    public Route route() {
        return route;
    }

    /**
     * Returns the {@link AnnotatedHttpService} that will handle the request.
     */
    public AnnotatedHttpService service() {
        return service;
    }

    /**
     * Returns the decorator of the {@link AnnotatedHttpService} which will be evaluated at service
     * registration time.
     */
    public Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>> decorator() {
        return decorator;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("route", route())
                          .add("service", service())
                          .add("decorator", decorator())
                          .toString();
    }
}
