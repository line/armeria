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

package com.linecorp.armeria.testing.junit5.server.mock;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A request that has been made to a {@link MockWebServerExtension}.
 */
public final class RecordedRequest {

    private final AggregatedHttpRequest request;
    private final ServiceRequestContext context;

    RecordedRequest(ServiceRequestContext context, AggregatedHttpRequest request) {
        this.request = requireNonNull(request, "request");
        this.context = requireNonNull(context, "context");
    }

    /**
     * The {@link AggregatedHttpRequest} received by the server.
     */
    public AggregatedHttpRequest request() {
        return request;
    }

    /**
     * The {@link ServiceRequestContext} created when handling the request. Can be used to, e.g., check whether
     * the request uses TLS.
     */
    public ServiceRequestContext context() {
        return context;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof RecordedRequest)) {
            return false;
        }

        final RecordedRequest that = (RecordedRequest) o;
        return request.equals(that.request) &&
               context.equals(that.context);
    }

    @Override
    public int hashCode() {
        // Simple hash since only used in tests.
        return Objects.hash(request, context);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("request", request)
                          .add("context", context)
                          .toString();
    }
}
