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

package com.linecorp.armeria.testing.junit.server.mockwebserver;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A request that has been made to a {@link MockWebServerExtension}.
 */
public class RecordedRequest {

    private final AggregatedHttpRequest request;
    private final ServiceRequestContext context;

    public RecordedRequest(AggregatedHttpRequest request, ServiceRequestContext context) {
        this.request = request;
        this.context = context;
    }

    /**
     * The {@link AggregatedHttpRequest} received by the server.
     */
    public AggregatedHttpRequest getRequest() {
        return request;
    }

    /**
     * The {@link ServiceRequestContext} created when handling the request. Can be used to, e.g., check whether
     * the request uses TLS.
     */
    public ServiceRequestContext getContext() {
        return context;
    }
}
