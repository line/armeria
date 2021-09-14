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

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;

import org.jboss.resteasy.core.Headers;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.plugins.server.BaseHttpRequest;
import org.jboss.resteasy.specimpl.ResteasyHttpHeaders;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.jboss.resteasy.spi.NotImplementedYetException;
import org.jboss.resteasy.spi.ResteasyAsynchronousContext;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.resteasy.CookieConverter;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AttributeKey;

abstract class AbstractResteasyHttpRequest<T> extends BaseHttpRequest {

    private final ServiceRequestContext requestContext;
    private final T request;
    private final ResteasyHttpHeaders httpHeaders;
    private final ResteasyAsynchronousContext asyncContext;

    AbstractResteasyHttpRequest(ServiceRequestContext requestContext,
                                T request, ResteasyHttpResponseImpl response,
                                ResteasyUriInfo uriInfo, SynchronousDispatcher dispatcher) {
        super(uriInfo);
        this.requestContext = requestContext;
        httpHeaders = convert(extractRequestHeaders(request));
        this.request = request;
        asyncContext = new ResteasyAsynchronousExecutionContextImpl(
                requireNonNull(dispatcher, "dispatcher"),
                this, requireNonNull(response, "response"));
    }

    ServiceRequestContext requestContext() {
        return requestContext;
    }

    T request() {
        return request;
    }

    abstract RequestHeaders extractRequestHeaders(T request);

    RequestHeaders requestHeaders() {
        return extractRequestHeaders(request);
    }

    @Override
    public HttpHeaders getHttpHeaders() {
        return httpHeaders;
    }

    @Override
    public MultivaluedMap<String, String> getMutableHeaders() {
        return httpHeaders.getMutableHeaders();
    }

    @Override
    public abstract InputStream getInputStream();

    @Override
    public void setInputStream(InputStream stream) {
        throw new UnsupportedOperationException("setInputStream");
    }

    @Override
    public String getHttpMethod() {
        return extractRequestHeaders(request).method().name();
    }

    @Override
    public void setHttpMethod(String method) {
        throw new UnsupportedOperationException("setHttpMethod");
    }

    @Override
    @Nullable
    public Object getAttribute(String name) {
        return requestContext.attr(AttributeKey.valueOf(name));
    }

    @Override
    public void setAttribute(String name, Object value) {
        requestContext.setAttr(AttributeKey.valueOf(name), value);
    }

    @Override
    public void removeAttribute(String name) {
        requestContext.setAttr(AttributeKey.valueOf(name), null);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        final Iterator<Entry<AttributeKey<?>, Object>> attrs = requestContext.attrs();
        return new Enumeration<String>() {

            @Override
            public boolean hasMoreElements() {
                return attrs.hasNext();
            }

            @Override
            public String nextElement() {
                return attrs.next().getKey().name();
            }
        };
    }

    @Override
    public ResteasyAsynchronousContext getAsyncContext() {
        return asyncContext;
    }

    @Override
    public void forward(String path) {
        throw new NotImplementedYetException("forward");
    }

    @Override
    public boolean wasForwarded() {
        return false;
    }

    @Override
    public String getRemoteAddress() {
        return requestContext.clientAddress().getHostAddress();
    }

    @Override
    public String getRemoteHost() {
        return requestContext.clientAddress().getHostName();
    }

    private static ResteasyHttpHeaders convert(RequestHeaders headers) {
        final Headers<String> reasteasyHeaders = new Headers<>();
        headers.forEach((key, value) -> reasteasyHeaders.add(key.toString(), value));
        final ResteasyHttpHeaders result = new ResteasyHttpHeaders(reasteasyHeaders);

        // This has to be a mutable Map! RESTEasy will fail otherwise.
        final Map<String, Cookie> cookies =
                CookieConverter.parse(headers.getAll(HttpHeaderNames.COOKIE));
        result.setCookies(cookies);
        return result;
    }
}
