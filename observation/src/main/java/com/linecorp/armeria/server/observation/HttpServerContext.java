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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.observation.transport.RequestReplyReceiverContext;

/**
 * TODO: Add me.
 */
final class HttpServerContext extends RequestReplyReceiverContext<HttpRequest, RequestLog> {

    private final ServiceRequestContext serviceRequestContext;
    private final HttpRequest httpRequest;

    HttpServerContext(ServiceRequestContext serviceRequestContext, HttpRequest httpRequest) {
        super((c, key) -> c.headers().get(key));
        this.serviceRequestContext = serviceRequestContext;
        this.httpRequest = httpRequest;
        setCarrier(httpRequest);
    }

    ServiceRequestContext getServiceRequestContext() {
        return serviceRequestContext;
    }

    HttpRequest getHttpRequest() {
        return httpRequest;
    }
}
