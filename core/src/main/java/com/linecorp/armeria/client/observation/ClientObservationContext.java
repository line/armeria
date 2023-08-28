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

package com.linecorp.armeria.client.observation;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;

import io.micrometer.observation.Observation.Context;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.transport.RequestReplySenderContext;

/**
 * A {@link Context} which may be used in conjunction with {@link ObservationClient}
 * to implement custom {@link ObservationConvention}s or {@link ObservationHandler}s.
 * <pre>{@code
 * ObservationConvention<ClientObservationContext> convention = ...
 * WebClient.builder()
 *          .decorator(ObservationClient.newDecorator(registry, convention))
 * ...
 * }</pre>
 */
@UnstableApi
public final class ClientObservationContext
        extends RequestReplySenderContext<RequestHeadersBuilder, RequestLog> {

    private final ClientRequestContext clientRequestContext;
    private final HttpRequest httpRequest;

    ClientObservationContext(ClientRequestContext clientRequestContext, RequestHeadersBuilder carrier,
                             HttpRequest httpRequest) {
        super(RequestHeadersBuilder::add);
        this.clientRequestContext = clientRequestContext;
        this.httpRequest = httpRequest;
        setCarrier(carrier);
    }

    /**
     * The {@link ClientRequestContext} associated with this {@link Context}.
     */
    public ClientRequestContext requestContext() {
        return clientRequestContext;
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
                          .add("clientRequestContext", clientRequestContext)
                          .add("httpRequest", httpRequest)
                          .toString();
    }
}
