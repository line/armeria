/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.resteasy;

import java.io.InputStream;

import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.common.resteasy.HttpMessageStream;
import com.linecorp.armeria.server.ServiceRequestContext;

final class StreamingResteasyHttpRequestImpl extends AbstractResteasyHttpRequest<HttpRequest> {

    private final HttpMessageStream requestStream;

    StreamingResteasyHttpRequestImpl(ServiceRequestContext requestContext,
                                     HttpRequest request, ResteasyHttpResponseImpl response,
                                     ResteasyUriInfo uriInfo, SynchronousDispatcher dispatcher) {
        super(requestContext, request, response, uriInfo, dispatcher);
        requestStream = HttpMessageStream.of(request);
    }

    @Override
    RequestHeaders extractRequestHeaders(HttpRequest request) {
        return request.headers();
    }

    @Override
    public InputStream getInputStream() {
        return requestStream.content();
    }
}
