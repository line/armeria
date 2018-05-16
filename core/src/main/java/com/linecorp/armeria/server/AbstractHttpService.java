/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.logging.RequestLogBuilder;

/**
 * A skeletal {@link HttpService} for easier HTTP service implementation.
 *
 * <p>This class provides the methods that handles the HTTP requests of the methods their names signify.
 * For example, {@link #doGet(ServiceRequestContext, HttpRequest) doGet()} method handles a
 * {@code GET} request.
 * <ul>
 *   <li>{@link #doOptions(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doGet(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doHead(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doPost(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doPut(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doPatch(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doDelete(ServiceRequestContext, HttpRequest)}</li>
 *   <li>{@link #doTrace(ServiceRequestContext, HttpRequest)}</li>
 * </ul>
 * These methods reject requests with a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response
 * by default. Override one of them to handle requests properly.
 */
public abstract class AbstractHttpService implements HttpService {

    /**
     * Serves the specified {@link HttpRequest} by delegating it to the matching {@code 'doMETHOD()'} method.
     * Override this method to perform an action for the requests of any HTTP methods:
     * <pre>{@code
     * > public class MyHttpService extends AbstractHttpService {
     * >     private final Map<HttpMethod, AtomicInteger> handledRequests = new ConcurrentHashMap<>();
     * >
     * >     @Override
     * >     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
     * >         final HttpResponse res = super.serve(ctx, req);
     * >         handledRequests.computeIfAbsent(
     * >                 req.method(), method -> new AtomicInteger()).incrementAndGet();
     * >         return res;
     * >     }
     * > }
     * }</pre>
     */
    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        try {
            switch (req.method()) {
                case OPTIONS:
                    return doOptions(ctx, req);
                case GET:
                    return doGet(ctx, req);
                case HEAD:
                    return doHead(ctx, req);
                case POST:
                    return doPost(ctx, req);
                case PUT:
                    return doPut(ctx, req);
                case PATCH:
                    return doPatch(ctx, req);
                case DELETE:
                    return doDelete(ctx, req);
                case TRACE:
                    return doTrace(ctx, req);
                default:
                    return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
            }
        } finally {
            final RequestLogBuilder logBuilder = ctx.logBuilder();
            if (!logBuilder.isRequestContentDeferred()) {
                // Set the requestContent to null by default.
                // An implementation can override this behavior by setting the requestContent in do*()
                // implementation or by calling deferRequestContent().
                logBuilder.requestContent(null, null);
            }

            // do*() methods are expected to set the serialization format before returning.
            logBuilder.serializationFormat(SerializationFormat.NONE);
        }
    }

    /**
     * Handles an {@link HttpMethod#OPTIONS OPTIONS} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doOptions(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpResponseWriter res = HttpResponse.streaming();
        doOptions(ctx, req, res);
        return res;
    }

    /**
     * Handles an {@link HttpMethod#OPTIONS OPTIONS} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     *
     * @deprecated Use {@link #doOptions(ServiceRequestContext, HttpRequest)}.
     */
    @Deprecated
    protected void doOptions(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
            throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#GET GET} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpResponseWriter res = HttpResponse.streaming();
        doGet(ctx, req, res);
        return res;
    }

    /**
     * Handles an {@link HttpMethod#GET GET} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     *
     * @deprecated Use {@link #doGet(ServiceRequestContext, HttpRequest)}.
     */
    @Deprecated
    protected void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
            throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#HEAD HEAD} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doHead(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpResponseWriter res = HttpResponse.streaming();
        doHead(ctx, req, res);
        return res;
    }

    /**
     * Handles an {@link HttpMethod#HEAD HEAD} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     *
     * @deprecated Use {@link #doHead(ServiceRequestContext, HttpRequest)}.
     */
    @Deprecated
    protected void doHead(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
            throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#POST POST} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpResponseWriter res = HttpResponse.streaming();
        doPost(ctx, req, res);
        return res;
    }

    /**
     * Handles an {@link HttpMethod#POST POST} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     *
     * @deprecated Use {@link #doPost(ServiceRequestContext, HttpRequest)}.
     */
    @Deprecated
    protected void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
            throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#PUT PUT} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doPut(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpResponseWriter res = HttpResponse.streaming();
        doPut(ctx, req, res);
        return res;
    }

    /**
     * Handles an {@link HttpMethod#PUT PUT} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     *
     * @deprecated Use {@link #doPut(ServiceRequestContext, HttpRequest)}.
     */
    @Deprecated
    protected void doPut(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
            throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#PATCH PATCH} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doPatch(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpResponseWriter res = HttpResponse.streaming();
        doPatch(ctx, req, res);
        return res;
    }

    /**
     * Handles an {@link HttpMethod#PATCH PATCH} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     *
     * @deprecated Use {@link #doPatch(ServiceRequestContext, HttpRequest)}.
     */
    @Deprecated
    protected void doPatch(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
            throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#DELETE DELETE} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doDelete(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpResponseWriter res = HttpResponse.streaming();
        doDelete(ctx, req, res);
        return res;
    }

    /**
     * Handles an {@link HttpMethod#DELETE DELETE} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     *
     * @deprecated Use {@link #doDelete(ServiceRequestContext, HttpRequest)}.
     */
    @Deprecated
    protected void doDelete(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
            throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#TRACE TRACE} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected HttpResponse doTrace(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final HttpResponseWriter res = HttpResponse.streaming();
        doTrace(ctx, req, res);
        return res;
    }

    /**
     * Handles an {@link HttpMethod#TRACE TRACE} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     *
     * @deprecated Use {@link #doTrace(ServiceRequestContext, HttpRequest)}.
     */
    @Deprecated
    protected void doTrace(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res)
            throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
