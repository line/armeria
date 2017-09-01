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

import com.linecorp.armeria.common.DefaultHttpResponse;
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
 * For example, {@link #doGet(ServiceRequestContext, HttpRequest, HttpResponseWriter) doGet()} method handles a
 * {@code GET} request.
 * <ul>
 *   <li>{@link #doOptions(ServiceRequestContext, HttpRequest, HttpResponseWriter)}</li>
 *   <li>{@link #doGet(ServiceRequestContext, HttpRequest, HttpResponseWriter)}</li>
 *   <li>{@link #doHead(ServiceRequestContext, HttpRequest, HttpResponseWriter)}</li>
 *   <li>{@link #doPost(ServiceRequestContext, HttpRequest, HttpResponseWriter)}</li>
 *   <li>{@link #doPut(ServiceRequestContext, HttpRequest, HttpResponseWriter)}</li>
 *   <li>{@link #doPatch(ServiceRequestContext, HttpRequest, HttpResponseWriter)}</li>
 *   <li>{@link #doDelete(ServiceRequestContext, HttpRequest, HttpResponseWriter)}</li>
 *   <li>{@link #doTrace(ServiceRequestContext, HttpRequest, HttpResponseWriter)}</li>
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
            final DefaultHttpResponse res = new DefaultHttpResponse();
            switch (req.method()) {
                case OPTIONS:
                    doOptions(ctx, req, res);
                    break;
                case GET:
                    doGet(ctx, req, res);
                    break;
                case HEAD:
                    doHead(ctx, req, res);
                    break;
                case POST:
                    doPost(ctx, req, res);
                    break;
                case PUT:
                    doPut(ctx, req, res);
                    break;
                case PATCH:
                    doPatch(ctx, req, res);
                    break;
                case DELETE:
                    doDelete(ctx, req, res);
                    break;
                case TRACE:
                    doTrace(ctx, req, res);
                    break;
                default:
                    res.respond(HttpStatus.METHOD_NOT_ALLOWED);
            }

            return res;
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
    protected void doOptions(ServiceRequestContext ctx,
                             HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#GET GET} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected void doGet(ServiceRequestContext ctx,
                         HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#HEAD HEAD} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected void doHead(ServiceRequestContext ctx,
                          HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#POST POST} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected void doPost(ServiceRequestContext ctx,
                          HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#PUT PUT} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected void doPut(ServiceRequestContext ctx,
                         HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#PATCH PATCH} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected void doPatch(ServiceRequestContext ctx,
                           HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#DELETE DELETE} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected void doDelete(ServiceRequestContext ctx,
                            HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }

    /**
     * Handles a {@link HttpMethod#TRACE TRACE} request.
     * This method sends a {@link HttpStatus#METHOD_NOT_ALLOWED 405 Method Not Allowed} response by default.
     */
    protected void doTrace(ServiceRequestContext ctx,
                           HttpRequest req, HttpResponseWriter res) throws Exception {
        res.respond(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
