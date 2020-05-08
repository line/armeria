/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.unsafe.server;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.unsafe.common.PooledHttpData;
import com.linecorp.armeria.unsafe.common.PooledHttpRequest;

/**
 * A skeletal {@link HttpService} for easier HTTP service implementation that publishes {@link PooledHttpData}.
 *
 * @see AbstractHttpService
 * @see PooledHttpData
 */
public abstract class AbstractPooledHttpService extends AbstractHttpService {

    /**
     * Handles an {@link HttpMethod#OPTIONS OPTIONS} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doOptions(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Override
    protected final HttpResponse doOptions(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return doOptions(ctx, PooledHttpRequest.of(req));
    }

    /**
     * Handles a {@link HttpMethod#GET GET} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doGet(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Override
    protected final HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return doGet(ctx, PooledHttpRequest.of(req));
    }

    /**
     * Handles a {@link HttpMethod#HEAD HEAD} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doHead(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Override
    protected final HttpResponse doHead(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return doHead(ctx, PooledHttpRequest.of(req));
    }

    /**
     * Handles a {@link HttpMethod#POST POST} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doPost(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Override
    protected final HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return doPost(ctx, PooledHttpRequest.of(req));
    }

    /**
     * Handles a {@link HttpMethod#PUT PUT} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doPut(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Override
    protected final HttpResponse doPut(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return doPut(ctx, PooledHttpRequest.of(req));
    }

    /**
     * Handles a {@link HttpMethod#PATCH PATCH} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doPatch(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Override
    protected final HttpResponse doPatch(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return doPatch(ctx, PooledHttpRequest.of(req));
    }

    /**
     * Handles a {@link HttpMethod#DELETE DELETE} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doDelete(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Override
    protected final HttpResponse doDelete(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return doDelete(ctx, PooledHttpRequest.of(req));
    }

    /**
     * Handles a {@link HttpMethod#TRACE TRACE} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doTrace(ServiceRequestContext ctx, PooledHttpRequest req) throws Exception {
        return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Override
    protected final HttpResponse doTrace(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return doTrace(ctx, PooledHttpRequest.of(req));
    }
}
