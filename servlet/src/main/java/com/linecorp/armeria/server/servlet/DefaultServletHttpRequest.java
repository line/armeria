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
package com.linecorp.armeria.server.servlet;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.SessionTrackingMode;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpUpgradeHandler;
import javax.servlet.http.Part;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AsciiString;

final class DefaultServletHttpRequest implements HttpServletRequest {
    private static final Logger logger = LoggerFactory.getLogger(DefaultServletHttpRequest.class);
    private static final SimpleDateFormat[] FORMATS_TEMPLATE = {
            new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
            new SimpleDateFormat("EEEEEE, dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH),
            new SimpleDateFormat("EEE MMMM d HH:mm:ss yyyy", Locale.ENGLISH)
    };

    private final ServiceRequestContext serviceRequestContext;
    private final DefaultServletContext servletContext;
    private final AggregatedHttpRequest httpRequest;
    private final Map<String, Object> attributeMap = new ConcurrentHashMap<>(16);
    private final SessionTrackingMode sessionIdSource = SessionTrackingMode.COOKIE;
    private final String servletPath;
    private final String requestURI;
    private final String characterEncoding;
    private final QueryParams queryParams;
    private final Map<String, String[]> parameters;

    @Nullable
    private final Cookie[] cookies;
    @Nullable
    private final String pathInfo;

    DefaultServletHttpRequest(ServiceRequestContext serviceRequestContext,
                              DefaultServletContext servletContext,
                              AggregatedHttpRequest httpRequest) throws IOException {
        this.serviceRequestContext = serviceRequestContext;
        this.servletContext = servletContext;
        this.httpRequest = httpRequest;
        final MediaType contentType = httpRequest.headers().contentType();
        if (contentType != null && contentType.charset() != null) {
            characterEncoding = contentType.charset().name();
        } else {
            characterEncoding = servletContext.getRequestCharacterEncoding();
        }

        requestURI = serviceRequestContext.path();
        queryParams = queryParamsOf(serviceRequestContext.query(), contentType, httpRequest);
        cookies = decodeCookie();
        servletPath = requestURI.substring(servletContext.getContextPath().length() + 1);
        pathInfo = decodePathInfo();

        final Builder<String, String[]> builder = ImmutableMap.builder();
        for (String name : queryParams.names()) {
            builder.put(name, queryParams.getAll(name).toArray(new String[0]));
        }
        parameters = builder.build();
    }

    private static QueryParams queryParamsOf(@Nullable String query,
                                             @Nullable MediaType contentType,
                                             @Nullable AggregatedHttpRequest message) {
        try {
            final QueryParams params1 = query != null ? QueryParams.fromQueryString(query) : null;
            QueryParams params2 = null;
            if (message != null && contentType != null && contentType.belongsTo(MediaType.FORM_DATA)) {
                // Respect 'charset' attribute of the 'content-type' header if it exists.
                final String body = message.content(contentType.charset(StandardCharsets.US_ASCII));
                if (!body.isEmpty()) {
                    params2 = QueryParams.fromQueryString(body);
                }
            }

            if (params1 == null || params1.isEmpty()) {
                return firstNonNull(params2, QueryParams.of());
            } else if (params2 == null || params2.isEmpty()) {
                return params1;
            } else {
                return QueryParams.builder()
                                  .sizeHint(params1.size() + params2.size())
                                  .add(params1)
                                  .add(params2)
                                  .build();
            }
        } catch (Exception e) {
            // If we failed to decode the query string, we ignore the exception raised here.
            // A missing parameter might be checked when invoking the annotated method.
            logger.debug("Failed to decode query string: {}", query, e);
            return QueryParams.of();
        }
    }

    @Nullable
    private Cookie[] decodeCookie() {
        final String cookieValue = httpRequest.headers().get(HttpHeaderNames.COOKIE);
        if (cookieValue != null) {
            final Cookies cookies = com.linecorp.armeria.common.Cookie.fromCookieHeader(cookieValue);
            return cookies.stream().map(c -> {
                final Cookie cookie = new Cookie(c.name(), c.value());
                if (c.domain() != null) {
                    cookie.setDomain(c.domain());
                }
                if (c.path() != null) {
                    cookie.setPath(c.path());
                }
                cookie.setSecure(c.isSecure());
                cookie.setMaxAge(Ints.saturatedCast(c.maxAge()));
                cookie.setHttpOnly(c.isHttpOnly());
                return new Cookie(c.name(), c.value());
            }).toArray(Cookie[]::new);
        } else {
            return null;
        }
    }

    @Nullable
    private String decodePathInfo() {
        final int index = getContextPath().length() + servletPath.length();
        return index < requestURI.length() ? requestURI.substring(index) : null;
    }

    @Override
    @Nullable
    public Cookie[] getCookies() {
        return cookies;
    }

    @Override
    public long getDateHeader(String name) {
        requireNonNull(name, "name");
        final String dateHeader = getHeader(name);
        if (dateHeader == null) {
            return -1;
        }
        for (DateFormat x : FORMATS_TEMPLATE) {
            try {
                return x.parse(dateHeader).getTime();
            } catch (Exception e) {
                // ignored.
            }
        }
        throw new IllegalArgumentException("failed to parse a date header: " + dateHeader);
    }

    @Override
    @Nullable
    public String getHeader(String name) {
        requireNonNull(name, "name");
        return httpRequest.headers().get(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(
                httpRequest.headers().names().stream()
                           .map(AsciiString::toString).collect(ImmutableList.toImmutableList()));
    }

    @Override
    public StringBuffer getRequestURL() {
        return new StringBuffer(httpRequest.scheme() + "://" + httpRequest.authority() + requestURI);
    }

    @Override
    @Nullable
    public String getPathInfo() {
        return pathInfo;
    }

    @Override
    @Nullable
    public String getQueryString() {
        return serviceRequestContext.query();
    }

    @Override
    public String getRequestURI() {
        return requestURI;
    }

    @Override
    public String getServletPath() {
        return servletPath;
    }

    @Override
    public HttpSession getSession(boolean create) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public HttpSession getSession() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String changeSessionId() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        requireNonNull(name, "name");
        return Collections.enumeration(
                httpRequest.headers().getAll(name).stream().collect(ImmutableList.toImmutableList()));
    }

    @Override
    public int getIntHeader(String name) {
        requireNonNull(name, "name");
        final String headerStringValue = getHeader(name);
        if (isNullOrEmpty(headerStringValue)) {
            return -1;
        }
        return Integer.parseInt(headerStringValue);
    }

    @Override
    public String getMethod() {
        return httpRequest.method().toString();
    }

    @Override
    public String getContextPath() {
        return servletContext.getContextPath();
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return sessionIdSource == SessionTrackingMode.COOKIE ||
               sessionIdSource == SessionTrackingMode.URL;
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return sessionIdSource == SessionTrackingMode.COOKIE;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return false;
    }

    @Override
    public boolean isRequestedSessionIdFromUrl() {
        return isRequestedSessionIdFromURL();
    }

    @Override
    public String getRequestedSessionId() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    @Nullable
    public Object getAttribute(String name) {
        requireNonNull(name, "name");
        return attributeMap.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(ImmutableSet.copyOf(attributeMap.keySet()));
    }

    @Override
    public String getCharacterEncoding() {
        return characterEncoding;
    }

    @Override
    public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
        throw new IllegalStateException("Can't set character encoding after request is initialized");
    }

    @Override
    public int getContentLength() {
        return httpRequest.content().length();
    }

    @Override
    public long getContentLengthLong() {
        return getContentLength();
    }

    @Override
    @Nullable
    public String getContentType() {
        final MediaType contentType = httpRequest.headers().contentType();
        return contentType == null ? null : contentType.toString();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    @Nullable
    public String getParameter(String name) {
        requireNonNull(name, "name");
        return queryParams.get(name);
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(ImmutableSet.copyOf(queryParams.names()));
    }

    @Override
    @Nullable
    public String[] getParameterValues(String name) {
        requireNonNull(name, "name");
        return parameters.get(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return parameters;
    }

    @Override
    public String getProtocol() {
        return serviceRequestContext.sessionProtocol().uriText();
    }

    @Override
    @Nullable
    public String getScheme() {
        return httpRequest.scheme();
    }

    @Override
    public String getServerName() {
        return serviceRequestContext.config().virtualHost().defaultHostname();
    }

    @Override
    public int getServerPort() {
        final InetSocketAddress socketAddress = serviceRequestContext.localAddress();
        return socketAddress.getPort();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getRemoteAddr() {
        return ((InetSocketAddress) serviceRequestContext.remoteAddress()).getAddress().getHostAddress();
    }

    @Override
    public String getRemoteHost() {
        return ((InetSocketAddress) serviceRequestContext.remoteAddress()).getHostName();
    }

    @Override
    public int getRemotePort() {
        return ((InetSocketAddress) serviceRequestContext.remoteAddress()).getPort();
    }

    @Override
    public void setAttribute(String name, @Nullable Object object) {
        requireNonNull(name, "name");
        if (object == null) {
            removeAttribute(name);
            return;
        }
        attributeMap.put(name, object);
    }

    @Override
    public void removeAttribute(String name) {
        requireNonNull(name, "name");
        attributeMap.remove(name);
    }

    @Override
    public Locale getLocale() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Enumeration<Locale> getLocales() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isSecure() {
        return serviceRequestContext.sessionProtocol().isTls();
    }

    @Override
    @Nullable
    public ServletRequestDispatcher getRequestDispatcher(String path) {
        return servletContext.getRequestDispatcher(path);
    }

    @Override
    public String getRealPath(String path) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getLocalName() {
        return serviceRequestContext.config().server().defaultHostname();
    }

    @Override
    public String getLocalAddr() {
        return serviceRequestContext.localAddress().toString();
    }

    @Override
    public int getLocalPort() {
        return getServerPort();
    }

    @Override
    public DefaultServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public AsyncContext startAsync() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isAsyncStarted() {
        return false;
    }

    @Override
    public boolean isAsyncSupported() {
        return false;
    }

    @Override
    public AsyncContext getAsyncContext() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public DispatcherType getDispatcherType() {
        return DispatcherType.REQUEST;
    }

    @Override
    public String getPathTranslated() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getAuthType() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getRemoteUser() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isUserInRole(String role) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean authenticate(HttpServletResponse response) throws IOException, ServletException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Principal getUserPrincipal() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void login(String username, String password) throws ServletException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void logout() throws ServletException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    @Nullable
    public Part getPart(String name) throws IOException, ServletException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> httpUpgradeHandlerClass) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
