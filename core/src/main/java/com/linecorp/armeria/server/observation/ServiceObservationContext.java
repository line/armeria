/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server.observation;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.transport.RequestReplyReceiverContext;

/**
 * A {@link Context} which may be used in conjunction with {@link ObservationService}
 * to implement custom {@link ObservationConvention}s or {@link ObservationHandler}s.
 * <pre>{@code
 * ObservationConvention<ServiceObservationContext> convention = ...
 * Server.builder()
 *       .decorator(ObservationService.newDecorator(registry, convention))
 * ...
 * }</pre>
 */
@UnstableApi
public final class ServiceObservationContext extends RequestReplyReceiverContext<HttpRequest, RequestLog> {

    private final ServiceRequestContext serviceRequestContext;
    private final HttpRequest httpRequest;

    ServiceObservationContext(ServiceRequestContext serviceRequestContext, HttpRequest httpRequest) {
        super((c, key) -> c.headers().get(key));
        this.serviceRequestContext = serviceRequestContext;
        this.httpRequest = httpRequest;
        setCarrier(httpRequest);
    }

    /**
     * The {@link ServiceRequestContext} associated with this {@link Context}.
     */
    public ServiceRequestContext requestContext() {
        return serviceRequestContext;
    }

    /**
     * The {@link HttpRequest} associated with this {@link Context}.
     */
    public HttpRequest httpRequest() {
        return httpRequest;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("serviceRequestContext", serviceRequestContext)
                          .add("httpRequest", httpRequest)
                          .toString();
    }
}
