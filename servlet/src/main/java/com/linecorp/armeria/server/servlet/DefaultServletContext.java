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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration.Dynamic;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.http.HttpServlet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.server.servlet.UrlMapper.Element;

/**
 * Servlet context (lifetime same as server).
 */
final class DefaultServletContext implements ServletContext {
    private static final Logger logger = LoggerFactory.getLogger(DefaultServletContext.class);
    private static final Set<SessionTrackingMode> defaultSessionTrackingModeSet =
            Sets.immutableEnumSet(SessionTrackingMode.COOKIE, SessionTrackingMode.URL);

    private final LogLevel level;
    private final UrlMapper<DefaultServletRegistration> servletUrlMapper = new UrlMapper<>(true);
    private final Map<String, Object> attributeMap = new HashMap<>();
    private final String contextPath;
    private final String servletContextName;

    private int sessionTimeout = 30; // unit: minutes
    private boolean initialized;
    private Map<String, String> initParamMap = new HashMap<>();
    private Map<String, DefaultServletRegistration> servletRegistrationMap = new HashMap<>();
    private Map<String, String> mimeMappings = new HashMap<>();
    private Set<SessionTrackingMode> sessionTrackingModeSet = defaultSessionTrackingModeSet;
    private String requestCharacterEncoding = ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET.name();
    private String responseCharacterEncoding = ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET.name();

    /**
     * Creates a new instance.
     */
    DefaultServletContext(String contextPath) {
        this(contextPath, LogLevel.DEBUG);
    }

    /**
     * Creates a new instance.
     */
    DefaultServletContext(String contextPath, LogLevel level) {
        requireNonNull(contextPath, "contextPath");
        requireNonNull(level, "level");
        this.contextPath = contextPath;
        this.level = level;
        if (contextPath.isEmpty()) {
            servletContextName = "";
        } else {
            servletContextName = contextPath.substring(1).replace("/", "_").trim();
        }
    }

    /**
     * Check servlet context is initialized.
     */
    boolean isInitialized() {
        return initialized;
    }

    /**
     * Set server started.
     */
    void init() {
        initialized = true;
        initParamMap = ImmutableMap.copyOf(initParamMap);
        servletRegistrationMap = ImmutableMap.copyOf(servletRegistrationMap);
        sessionTrackingModeSet = ImmutableSet.copyOf(sessionTrackingModeSet);
        mimeMappings = ImmutableMap.copyOf(mimeMappings);
    }

    void mimeMapping(String extension, String mimeType) {
        requireNonNull(extension, "extension");
        requireNonNull(mimeType, "mimeType");
        mimeMappings.put(extension, mimeType);
    }

    void mimeMappings(Map<String, String> mappings) {
        mimeMappings.putAll(requireNonNull(mappings, "mappings"));
    }

    /**
     * Get servlet path.
     */
    @Nullable
    String getServletPath(String uri) {
        final Element element = servletUrlMapper.getMapping(UrlMapper.normalizePath(uri));
        return element == null ? null : element.path.substring(contextPath.length());
    }

    @Override
    public int getSessionTimeout() {
        return sessionTimeout;
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public DefaultServletContext getContext(String uripath) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public int getMajorVersion() {
        return 4;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return 4;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return 0;
    }

    @Override
    @Nullable
    public String getMimeType(@Nullable String file) {
        if (file == null) {
            return null;
        }
        final int period = file.lastIndexOf('.');
        if (period < 0) {
            return null;
        }
        final String extension = file.substring(period + 1);
        if (extension.isEmpty()) {
            return null;
        }
        return mimeMappings.get(extension);
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getRealPath(String path) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    @Nullable
    public ServletRequestDispatcher getRequestDispatcher(String path) {
        requireNonNull(path, "path");
        final UrlMapper.Element<DefaultServletRegistration> element =
                servletUrlMapper.getMapping(UrlMapper.normalizePath(path));
        if (element == null) {
            return null;
        }
        return new ServletRequestDispatcher(new ServletFilterChain(element.getObject()),
                                            element.pattern, element);
    }

    @Override
    @Nullable
    public ServletRequestDispatcher getNamedDispatcher(String name) {
        requireNonNull(name, "name");
        name = UrlMapper.normalizePath(name);
        final DefaultServletRegistration servletRegistration = getServletRegistration(name);
        if (servletRegistration == null) {
            return null;
        }
        return new ServletRequestDispatcher(new ServletFilterChain(servletRegistration), name);
    }

    @Override
    @Nullable
    public Servlet getServlet(String name) throws ServletException {
        requireNonNull(name, "name");
        final DefaultServletRegistration registration = servletRegistrationMap.get(name);
        if (registration == null) {
            return null;
        }
        return registration.getServlet();
    }

    @Override
    public Enumeration<Servlet> getServlets() {
        return Collections.enumeration(servletRegistrationMap.values()
                                                             .stream()
                                                             .map(DefaultServletRegistration::getServlet)
                                                             .collect(ImmutableList.toImmutableList()));
    }

    @Override
    public Enumeration<String> getServletNames() {
        return Collections.enumeration(servletRegistrationMap.values()
                                                             .stream()
                                                             .map(DefaultServletRegistration::getName)
                                                             .collect(ImmutableList.toImmutableList()));
    }

    @Override
    public void log(String message) {
        requireNonNull(message, "message");
        level.log(logger, message);
    }

    @Override
    public void log(Exception exception, String message) {
        requireNonNull(exception, "exception");
        requireNonNull(message, "message");
        level.log(logger, message, exception);
    }

    @Override
    public void log(String message, Throwable throwable) {
        requireNonNull(message, "message");
        requireNonNull(throwable, "throwable");
        level.log(logger, message, throwable);
    }

    @Override
    public String getServerInfo() {
        return ArmeriaHttpUtil.SERVER_HEADER +
               " (JDK " + SystemInfo.javaVersion() + ";" + SystemInfo.osType().name() + ")";
    }

    @Override
    public String getInitParameter(String name) {
        requireNonNull(name, "name");
        return initParamMap.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParamMap.keySet());
    }

    @Override
    public boolean setInitParameter(String name, @Nullable String value) {
        ensureUninitialized("setInitParameter");
        requireNonNull(name, "name");
        return initParamMap.putIfAbsent(name, value) == null;
    }

    @Override
    public Object getAttribute(String name) {
        requireNonNull(name, "name");
        return attributeMap.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(ImmutableSet.copyOf(attributeMap.keySet()));
    }

    @Override
    public void setAttribute(String name, @Nullable Object object) {
        requireNonNull(name);
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
    public String getServletContextName() {
        return servletContextName;
    }

    @Override
    public DefaultServletRegistration addServlet(String servletName, String className) {
        ensureUninitialized("addServlet");
        checkArgument(!isNullOrEmpty(servletName), "servletName is empty)", servletName);
        requireNonNull(className, "className");
        try {
            //noinspection unchecked
            return addServlet(servletName, (Class<HttpServlet>) Class.forName(className));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to add a servlet. servletName: " + servletName, e);
        }
    }

    @Override
    public DefaultServletRegistration addServlet(String servletName, Servlet servlet) {
        ensureUninitialized("addServlet");
        checkArgument(!isNullOrEmpty(servletName),
                      "servletName: %s (expected: not null and empty)", servletName);
        requireNonNull(servlet, "servlet");
        servletName = UrlMapper.normalizePath(servletName);
        final DefaultServletRegistration servletRegistration =
                new DefaultServletRegistration(servletName, servlet, this, servletUrlMapper, initParamMap);
        servletRegistrationMap.put(servletName, servletRegistration);
        return servletRegistration;
    }

    @Override
    public DefaultServletRegistration addServlet(String servletName, Class<? extends Servlet> servletClass) {
        ensureUninitialized("addServlet");
        checkArgument(!isNullOrEmpty(servletName),
                      "servletName: %s (expected: not null and empty)", servletName);
        requireNonNull(servletClass, "servletClass");
        try {
            return addServlet(servletName, createServlet(servletClass));
        } catch (ServletException e) {
            throw new RuntimeException("Failed to add a servlet. servletName: " + servletName, e);
        }
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
        try {
            requireNonNull(clazz, "clazz");
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create a servlet: " + clazz.getSimpleName(), e);
        }
    }

    @Override
    @Nullable
    public DefaultServletRegistration getServletRegistration(String servletName) {
        requireNonNull(servletName, "servletName");
        return servletRegistrationMap.get(servletName);
    }

    @Override
    public Map<String, DefaultServletRegistration> getServletRegistrations() {
        return servletRegistrationMap;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Nullable
    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        requireNonNull(filterName, "filterName");
        return null;
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return ImmutableMap.of();
    }

    @Override
    public SessionCookieConfig getSessionCookieConfig() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return defaultSessionTrackingModeSet;
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return sessionTrackingModeSet;
    }

    @Override
    public void addListener(String className) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public <T extends EventListener> void addListener(T listener) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public ClassLoader getClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public void declareRoles(String... roleNames) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public String getVirtualServerName() {
        return ArmeriaHttpUtil.SERVER_HEADER +
               " (JDK " + SystemInfo.javaVersion() + ";" + SystemInfo.osType().name() + ")";
    }

    @Override
    public String getRequestCharacterEncoding() {
        return requestCharacterEncoding;
    }

    @Override
    public void setRequestCharacterEncoding(String requestCharacterEncoding) {
        ensureUninitialized("setRequestCharacterEncoding");
        requireNonNull(requestCharacterEncoding, "requestCharacterEncoding");
        this.requestCharacterEncoding = requestCharacterEncoding;
    }

    @Override
    public String getResponseCharacterEncoding() {
        return responseCharacterEncoding;
    }

    @Override
    public void setResponseCharacterEncoding(String responseCharacterEncoding) {
        ensureUninitialized("setResponseCharacterEncoding");
        requireNonNull(responseCharacterEncoding, "responseCharacterEncoding");
        this.responseCharacterEncoding = responseCharacterEncoding;
    }

    @Override
    public Dynamic addJspFile(String jspName, String jspFile) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    private void ensureUninitialized(String name) {
        requireNonNull(name, "name");
        checkState(!initialized, "Can't execute %s after the servlet context is initialized.", name);
    }
}
