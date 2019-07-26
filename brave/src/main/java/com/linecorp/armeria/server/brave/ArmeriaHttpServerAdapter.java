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

package com.linecorp.armeria.server.brave;

import java.util.List;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.internal.brave.SpanTags;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;

import brave.Span;
import brave.http.HttpServerAdapter;

final class ArmeriaHttpServerAdapter extends HttpServerAdapter<RequestLog, RequestLog> {

    private static final ArmeriaHttpServerAdapter INSTANCE = new ArmeriaHttpServerAdapter();

    static ArmeriaHttpServerAdapter get() {
        return INSTANCE;
    }

    private ArmeriaHttpServerAdapter() {
    }

    @Override
    public String method(RequestLog requestLog) {
        return requestLog.method().name();
    }

    /**
     * Original implementation is calling {@link HttpServerAdapter#url(Object)} which needs
     * {@link RequestLog#scheme()} is not available at {@link RequestLogAvailability#REQUEST_START}.
     * We need to use {@link RequestLog#path()} directly.
     *
     * @see brave.http.HttpAdapter#path(Object)
     */
    @Override
    public String path(RequestLog requestLog) {
        return requestLog.path();
    }

    @Override
    @Nullable
    public String url(RequestLog requestLog) {
        return SpanTags.generateUrl(requestLog);
    }

    @Override
    @Nullable
    public String requestHeader(RequestLog requestLog, String name) {
        if (!requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)) {
            return null;
        }
        return requestLog.requestHeaders().get(name);
    }

    @Override
    @Nullable
    public Integer statusCode(RequestLog requestLog) {
        final int result = statusCodeAsInt(requestLog);
        return result != 0 ? result : null;
    }

    @Override
    public int statusCodeAsInt(RequestLog requestLog) {
        if (!requestLog.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)) {
            return 0;
        }
        return requestLog.status().code();
    }

    @Override
    public String methodFromResponse(RequestLog requestLog) {
        return requestLog.method().name();
    }

    @Override
    @Nullable
    public String route(RequestLog response) {
        assert response.context() instanceof ServiceRequestContext;
        final Route route = ((ServiceRequestContext) response.context()).route();
        final List<String> paths = route.paths();
        switch (route.pathType()) {
            case EXACT:
            case PREFIX:
            case PARAMETERIZED:
                return paths.get(1);
            case REGEX:
                return paths.get(paths.size() - 1);
            case REGEX_WITH_PREFIX:
                return paths.get(1) + paths.get(0);
        }
        return null;
    }

    /**
     * Returns the authority of the {@link Request}.
     */
    @Nullable
    public String authority(RequestLog requestLog) {
        if (!requestLog.isAvailable(RequestLogAvailability.REQUEST_HEADERS)) {
            return null;
        }
        return requestLog.authority();
    }

    /**
     * Returns the {@link SessionProtocol#uriText()} of the {@link Request}.
     */
    @Nullable
    public String protocol(RequestLog requestLog) {
        if (!requestLog.isAvailable(RequestLogAvailability.SCHEME)) {
            return null;
        }
        return requestLog.scheme().sessionProtocol().uriText();
    }

    /**
     * Returns the {@link SerializationFormat#uriText()} if it's not {@link SerializationFormat#NONE}.
     */
    @Nullable
    public String serializationFormat(RequestLog requestLog) {
        if (!requestLog.isAvailable(RequestLogAvailability.SCHEME)) {
            return null;
        }
        final SerializationFormat serFmt = requestLog.scheme().serializationFormat();
        return serFmt == SerializationFormat.NONE ? null : serFmt.uriText();
    }

    /**
     * Returns the method name if {@link RequestLog#requestContent()} is {@link RpcRequest}.
     */
    @Nullable
    public String rpcMethod(RequestLog requestLog) {
        if (!requestLog.isAvailable(RequestLogAvailability.REQUEST_CONTENT)) {
            return null;
        }
        final Object requestContent = requestLog.requestContent();
        return requestContent instanceof RpcRequest ? ((RpcRequest) requestContent).method() : null;
    }

    /**
     * This sets the client IP:port to the {@link RequestContext#remoteAddress()}
     * if the {@linkplain HttpServerAdapter#parseClientIpAndPort default parsing} fails.
     */
    @Override
    public boolean parseClientIpAndPort(RequestLog requestLog, Span span) {
        if (parseClientIpFromXForwardedFor(requestLog, span)) {
            return true;
        }
        return SpanTags.updateRemoteEndpoint(span, requestLog);
    }
}
