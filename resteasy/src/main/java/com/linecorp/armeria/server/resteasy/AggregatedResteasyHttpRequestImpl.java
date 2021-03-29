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

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.plugins.server.BaseHttpRequest;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Implements {@link BaseHttpRequest}.
 */
final class AggregatedResteasyHttpRequestImpl extends AbstractResteasyHttpRequest<AggregatedHttpRequest> {

    private static final InputStream EMPTY_DATA_STREAM = new ByteArrayInputStream(new byte[0]);

    AggregatedResteasyHttpRequestImpl(ServiceRequestContext requestContext,
                                      AggregatedHttpRequest request, ResteasyHttpResponseImpl response,
                                      ResteasyUriInfo uriInfo, SynchronousDispatcher dispatcher) {
        super(requestContext, request, response, uriInfo, dispatcher);
    }

    @Override
    RequestHeaders extractRequestHeaders(AggregatedHttpRequest request) {
        return request.headers();
    }

    @Override
    public InputStream getInputStream() {
        final HttpData content = request().content();
        return content.isEmpty() ? EMPTY_DATA_STREAM : content.toInputStream();
    }
}
