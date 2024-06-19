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

package com.linecorp.armeria.client.endpoint.healthcheck;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.util.AsyncCloseable;

/**
 * A builder for creating a new {@link HealthCheckedEndpointGroup} that sends HTTP health check requests.
 */
public final class HealthCheckedEndpointGroupBuilder
        extends AbstractHealthCheckedEndpointGroupBuilder<HealthCheckedEndpointGroupBuilder> {

    private final String path;
    private boolean useGet;

    /**
     * Returns a newly created {@link HealthCheckedEndpointGroupBuilder} that builds
     * a {@link HealthCheckedEndpointGroup} which sends HTTP {@code HEAD} health check requests.
     *
     * @param delegate the {@link EndpointGroup} that provides the candidate {@link Endpoint}s
     * @param path     the HTTP request path, e.g. {@code "/internal/l7check"}
     */
    HealthCheckedEndpointGroupBuilder(EndpointGroup delegate, String path) {
        super(delegate);
        this.path = requireNonNull(path, "path");
    }

    /**
     * Sets whether to use HTTP {@code GET} method instead of {@code HEAD} when sending a health check request.
     * By default, {@code HEAD} method is used. This can be useful when the health check requests are failing
     * due to a bad request or an authorization failure and you want to learn why.
     */
    public HealthCheckedEndpointGroupBuilder useGet(boolean useGet) {
        this.useGet = useGet;
        return this;
    }

    @Override
    protected Function<? super HealthCheckerContext, ? extends AsyncCloseable> newCheckerFactory() {
        return new HttpHealthCheckerFactory(path, useGet);
    }

    private static class HttpHealthCheckerFactory implements Function<HealthCheckerContext, AsyncCloseable> {

        private final String path;
        private final boolean useGet;

        HttpHealthCheckerFactory(String path, boolean useGet) {
            this.path = path;
            this.useGet = useGet;
        }

        @Override
        public AsyncCloseable apply(HealthCheckerContext ctx) {
            final HttpHealthChecker checker = new HttpHealthChecker(ctx, path, useGet);
            checker.start();
            return checker;
        }
    }
}
