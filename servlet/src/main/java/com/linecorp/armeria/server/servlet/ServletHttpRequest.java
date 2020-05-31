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

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.Principal;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
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
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.servlet.util.LinkedMultiValueMap;
import com.linecorp.armeria.server.servlet.util.ServletUtil;

import io.netty.buffer.Unpooled;

/**
 * The servlet request.
 */
public class ServletHttpRequest implements HttpServletRequest {
    public static final int HTTPS_PORT = 443;
    public static final int HTTP_PORT = 80;
    public static final String HTTPS = "https";
    public static final String HTTP = "http";
    public static final String POST = "POST";

    private static final Logger logger = LoggerFactory.getLogger(ServletHttpRequest.class);
    private static final Locale[] DEFAULT_LOCALS = { Locale.getDefault() };
    private static final String RFC1123_DATE = "EEE, dd MMM yyyy HH:mm:ss zzz";
    private static final SimpleDateFormat[] FORMATS_TEMPLATE = {
            new SimpleDateFormat(RFC1123_DATE, Locale.ENGLISH),
            new SimpleDateFormat("EEEEEE, dd-MMM-yy HH:mm:ss zzz", Locale.ENGLISH),
            new SimpleDateFormat("EEE MMMM d HH:mm:ss yyyy", Locale.ENGLISH)
    };

    private final ServiceRequestContext serviceRequestContext;
    private final DefaultServletContext servletContext;
    private final AggregatedHttpRequest httpRequest;
    private final DefaultServletInputStream inputStream = new DefaultServletInputStream();
    private final Map<String, Object> attributeMap = new ConcurrentHashMap<>(16);

    private LinkedMultiValueMap<String, String> parameterMap = new LinkedMultiValueMap<>();
    private List<Part> fileUploadList = new ArrayList<>();
    private Boolean asyncSupportedFlag = true;

    @Nullable
    private String servletPath;
    @Nullable
    private String queryString;
    @Nullable
    private String pathInfo;
    @Nullable
    private String requestURI;
    @Nullable
    private String characterEncoding;
    @Nullable
    private SessionTrackingMode sessionIdSource;
    private boolean usingInputStreamFlag;
    @Nullable
    private BufferedReader reader;
    @Nullable
    private Cookie[] cookies;
    @Nullable
    private Locale[] locales;
    @Nullable
    private ServletRequestDispatcher dispatcher;

    private final Map<String, String[]> unmodifiableParameterMap = new AbstractMap<String, String[]>() {
        @Override
        public Set<Entry<String, String[]>> entrySet() {
            if (isEmpty()) {
                return Collections.emptySet();
            }
            return parameterMap.entrySet()
                               .stream()
                               .map(x -> new SimpleImmutableEntry<>(
                                       x.getKey(),
                                       x.getValue() != null ? x.getValue().toArray(
                                               new String[x.getValue().size()]) : null))
                               .collect(Collectors.toSet());
        }

        @Override
        @Nullable
        public String[] get(@Nullable Object key) {
            final List<String> value = parameterMap.get(key);
            if (value == null) {
                return null;
            } else {
                return value.toArray(new String[value.size()]);
            }
        }

        @Override
        public boolean containsKey(Object key) {
            requireNonNull(key, "key");
            return parameterMap.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            requireNonNull(value, "value");
            return parameterMap.toSingleValueMap().containsValue(value);
        }

        @Override
        public int size() {
            return parameterMap.size();
        }
    };

    protected ServletHttpRequest(ServiceRequestContext serviceRequestContext,
                                 DefaultServletContext servletContext,
                                 AggregatedHttpRequest request) {
        requireNonNull(serviceRequestContext, "serviceRequestContext");
        requireNonNull(servletContext, "servletContext");
        requireNonNull(request, "request");

        this.serviceRequestContext = serviceRequestContext;
        this.servletContext = servletContext;
        httpRequest = request;
        inputStream.setContent(Unpooled.wrappedBuffer(request.content().array()));
        decodeUrlParameter();
        decodeBody();
        decodeCookie();
        decodeLocale();
        getProtocol();
        getScheme();
        decodePaths();
    }

    void setDispatcher(ServletRequestDispatcher dispatcher) {
        requireNonNull(dispatcher, "dispatcher");
        this.dispatcher = dispatcher;
    }

    void setAsyncSupportedFlag(boolean asyncSupportedFlag) {
        this.asyncSupportedFlag = asyncSupportedFlag;
    }

    /**
     * Get netty request.
     */
    public AggregatedHttpRequest getHttpRequest() {
        return httpRequest;
    }

    private Map<String, Object> getAttributeMap() {
        return attributeMap;
    }

    /**
     * Parse area.
     */
    private void decodeLocale() {
        final String headerValue = getHeader(HttpHeaderNames.ACCEPT_LANGUAGE.toString());
        if (headerValue == null) {
            locales = DEFAULT_LOCALS;
        } else {
            locales = Arrays.stream(getHeader(HttpHeaderNames.ACCEPT_LANGUAGE.toString()).split(","))
                            .map(x -> x.split(";").length > 0 ?
                                      Locale.forLanguageTag(x.split(";")[0].trim())
                                                              : Locale.forLanguageTag(x.trim())
                            ).toArray(Locale[]::new);
        }
    }

    /**
     * Parsing coding.
     */
    private void decodeCharacterEncoding() {
        characterEncoding = ServletUtil.decodeCharacterEncoding(getContentType());
        if (characterEncoding == null) {
            characterEncoding = servletContext.getRequestCharacterEncoding();
        }
    }

    /**
     * parse parameter specification.
     */
    private void decodeBody() {
        if (POST.equalsIgnoreCase(getMethod()) && getContentLength() > 0) {
            ServletUtil.decodeBody(parameterMap, httpRequest.content().array(), getContentType());
        }
    }

    /**
     * Parsing URL parameters.
     */
    private void decodeUrlParameter() {
        final Charset charset = Charset.forName(getCharacterEncoding());
        ServletUtil.decodeByUrl(parameterMap, httpRequest.path(), charset);
    }

    /**
     * Parsing the cookie.
     */
    private void decodeCookie() {
        final String value = getHeader(HttpHeaderNames.COOKIE.toString());
        if (!isNullOrEmpty(value)) {
            final Collection<Cookie> cookieSet = ServletUtil.decodeCookie(value);
            if (!cookieSet.isEmpty()) {
                cookies = cookieSet.toArray(new Cookie[0]);
            }
        }
    }

    /**
     * Parsing path.
     */
    private void decodePaths() {
        String requestURI = httpRequest.path();
        final String queryString;
        final int queryInx = requestURI.indexOf('?');
        if (queryInx > -1) {
            queryString = requestURI.substring(queryInx + 1);
            requestURI = requestURI.substring(0, queryInx);
        } else {
            queryString = null;
        }
        if (requestURI.length() > 1 && requestURI.charAt(0) == '/' && requestURI.charAt(1) == '/') {
            requestURI = requestURI.substring(1);
        }

        this.requestURI = requestURI;
        this.queryString = queryString;
    }

    @Override
    @Nullable
    public Cookie[] getCookies() {
        return cookies;
    }

    /**
     * Get date header.
     */
    @Override
    public long getDateHeader(String name) {
        requireNonNull(name, "name");
        for (DateFormat x : FORMATS_TEMPLATE) {
            try {
                final Date date = x.parse(getHeader(name));
                if (date != null) {
                    return date.getTime();
                }
            } catch (ParseException e) {
                logger.info("Try parse " + getHeader(name) + " to date");
            }
        }
        return -1;
    }

    /**
     * The getHeader method returns the header for the given header name.
     * @param name name.
     * @return header value.
     */
    @Override
    @Nullable
    public String getHeader(String name) {
        requireNonNull(name, "name");
        final Object value = httpRequest.headers().get(name);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(
                httpRequest.headers().names().stream()
                           .map(x -> x.toString()).collect(ImmutableList.toImmutableList()));
    }

    /**
     * Copy the implementation of tomcat.
     * @return Request URL.
     */
    @Override
    public StringBuffer getRequestURL() {
        final StringBuffer url = new StringBuffer();
        final String scheme = getScheme();
        int port = getServerPort();
        if (port < 0) {
            port = HTTP_PORT;
        }

        url.append(scheme);
        url.append("://");
        url.append(getServerName());
        if ((HTTP.equals(scheme) && (port != HTTP_PORT)) ||
            (HTTPS.equals(scheme) && (port != HTTPS_PORT))) {
            url.append(':');
            url.append(port);
        }
        url.append(getRequestURI());
        return url;
    }

    /**
     * PathInfo：Part of the request Path that is not part of the Context Path or Servlet Path.
     * If there's no extra path, it's either null,
     * Or a string that starts with '/'.
     * @return pathInfo.
     */
    @Override
    @Nullable
    public String getPathInfo() {
        return pathInfo;
    }

    @Override
    @Nullable
    public String getQueryString() {
        return queryString;
    }

    @Override
    public String getRequestURI() {
        return requestURI;
    }

    /**
     * Servlet Path: the Path section corresponds directly to the mapping of the activation request.
     * The path starts with the "/" character, if the request is in the "/ *" or "" mode."
     * matches, in which case it is an empty string.
     * @return servletPath.
     */
    @Override
    public String getServletPath() {
        if (servletPath == null) {
            servletPath = servletContext.getServletPath(requestURI).replaceFirst(
                    servletContext.getContextPath(), "");
        }
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

    /**
     * servlet standard:
     * returns the value of the specified request header
     * as int. If the request has no title
     * the name specified by this method returns -1. if This method does not convert headers to integers
     * throws a NumberFormatException code. The first name is case insensitive.
     * @param name  specifies the name of the request header
     * @exception NumberFormatException If the header value cannot be converted to an int.
     * @return An integer request header representing a value or -1 if the request does not return -1.
     */
    @Override
    public int getIntHeader(String name) {
        requireNonNull(name, "name");
        final String headerStringValue = getHeader(name);
        if (headerStringValue == null) {
            return -1;
        }
        return Integer.parseInt(headerStringValue);
    }

    @Override
    public String getMethod() {
        return httpRequest.method().toString();
    }

    /**
     * Context Path: the Path prefix associated with the ServletContext is part of this servlet.
     * If the context is web-based the server's URL namespace based on the "default" context,
     * then the path will be an empty string. Otherwise, if the context is not
     * server-based namespaces, so the path starts with /, but does not end with /.
     */
    @Override
    public String getContextPath() {
        return getServletContext().getContextPath();
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
        return isRequestedSessionIdFromUrl();
    }

    @Override
    public boolean isRequestedSessionIdFromUrl() {
        return sessionIdSource == SessionTrackingMode.URL;
    }

    @Override
    public String getRequestedSessionId() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    @Nullable
    public Object getAttribute(String name) {
        requireNonNull(name, "name");
        final Object value = getAttributeMap().get(name);
        return value;
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(ImmutableSet.copyOf(getAttributeMap().keySet()));
    }

    @Override
    public String getCharacterEncoding() {
        if (characterEncoding == null) {
            decodeCharacterEncoding();
        }
        return characterEncoding;
    }

    @Override
    public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
        requireNonNull(env, "env");
        characterEncoding = env;
    }

    @Override
    public int getContentLength() {
        return (int) getContentLengthLong();
    }

    @Override
    public long getContentLengthLong() {
        return Integer.parseInt(httpRequest.headers().get(HttpHeaderNames.CONTENT_LENGTH)
                                           .replaceAll("\\[|\\]", ""));
    }

    @Override
    @Nullable
    public String getContentType() {
        return getHeader(HttpHeaderNames.CONTENT_TYPE.toString());
    }

    @Override
    public DefaultServletInputStream getInputStream() throws IOException {
        if (reader != null) {
            throw new IllegalStateException("getReader() has already been called for this request");
        }
        usingInputStreamFlag = true;
        return inputStream;
    }

    @Override
    @Nullable
    public String getParameter(String name) {
        requireNonNull(name, "name");
        final String[] values = getParameterMap().get(name);
        if (values == null || values.length == 0) {
            return null;
        }
        return values[0];
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(ImmutableSet.copyOf(getParameterMap().keySet()));
    }

    @Override
    @Nullable
    public String[] getParameterValues(String name) {
        requireNonNull(name, "name");
        return getParameterMap().get(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return unmodifiableParameterMap;
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
    @Nullable
    public String getServerName() {
        return ((InetSocketAddress) serviceRequestContext.remoteAddress()).getAddress().getHostAddress();
    }

    @Override
    public int getServerPort() {
        return serviceRequestContext.config().server().activeLocalPort();
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (usingInputStreamFlag) {
            throw new IllegalStateException("getInputStream() has already been called for this request");
        }
        if (reader == null) {
            synchronized (this) {
                if (reader == null) {
                    String charset = getCharacterEncoding();
                    if (charset == null) {
                        charset = getServletContext().getRequestCharacterEncoding();
                    }
                    reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
                }
            }
        }
        return reader;
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
        return getServerPort();
    }

    @Override
    public void setAttribute(String name, @Nullable Object object) {
        requireNonNull(name, "name");
        if (object == null) {
            removeAttribute(name);
            return;
        }
        getAttributeMap().put(name, object);
    }

    @Override
    public void removeAttribute(String name) {
        requireNonNull(name, "name");
        getAttributeMap().remove(name);
    }

    @Override
    @Nullable
    public Locale getLocale() {
        if (locales == null || locales.length == 0) {
            return null;
        }
        return locales[0];
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return Collections.enumeration(Arrays.stream(locales).collect(ImmutableList.toImmutableList()));
    }

    @Override
    public boolean isSecure() {
        return HTTPS.equals(getScheme());
    }

    @Override
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
        return asyncSupportedFlag;
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
    @Nullable
    public String getPathTranslated() {
        if (isNullOrEmpty(servletContext.getContextPath())) {
            return null;
        }
        return pathInfo == null ? null : servletContext.getRealPath(pathInfo);
    }

    /**
     * "BASIC", or "DIGEST", or "SSL".
     * @return Authentication type.
     */
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
        return fileUploadList;
    }

    @Override
    @Nullable
    public Part getPart(String name) throws IOException, ServletException {
        requireNonNull(name, "name");
        return getParts().stream().filter(x -> name.equals(x.getName())).findAny().orElse(null);
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> httpUpgradeHandlerClass) {
        throw new UnsupportedOperationException("Not supported yet.");
    }
}
