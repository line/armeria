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

package com.linecorp.armeria.client.observation;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.logging.RequestLog;

import io.micrometer.common.lang.NonNull;
import io.micrometer.observation.transport.RequestReplySenderContext;

public final class HttpClientContext extends RequestReplySenderContext<RequestHeadersBuilder, RequestLog> {

    private final ClientRequestContext clientRequestContext;
    private final HttpRequest httpRequest;

    public HttpClientContext(ClientRequestContext clientRequestContext, @NonNull RequestHeadersBuilder carrier,
                             HttpRequest httpRequest) {
        super(RequestHeadersBuilder::add);
        this.clientRequestContext = clientRequestContext;
        this.httpRequest = httpRequest;
        setCarrier(carrier);
    }

    public ClientRequestContext getClientRequestContext() {
        return clientRequestContext;
    }

    public HttpRequest getHttpRequest() {
        return httpRequest;
    }
}
