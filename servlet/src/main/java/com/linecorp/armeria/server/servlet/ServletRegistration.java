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

import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.servlet.MultipartConfigElement;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration.Dynamic;
import javax.servlet.ServletSecurityElement;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.server.servlet.util.UrlMapper;

/**
 * The servlet supportPipeline.
 */
public class ServletRegistration implements Dynamic {
    private final String servletName;
    private final Servlet servlet;
    private final ServletConfig servletConfig;
    private final DefaultServletContext servletContext;
    private final UrlMapper<ServletRegistration> urlMapper;
    private final Set<String> mappingSet = new HashSet<>();
    private final Map<String, String> initParameterMap;

    private boolean asyncSupported = true;
    private int loadOnStartup = -1;
    private AtomicBoolean initServlet = new AtomicBoolean();

    @Nullable
    private MultipartConfigElement multipartConfigElement;
    @Nullable
    private ServletSecurityElement servletSecurityElement;
    @Nullable
    private String roleName;

    /**
     * Creates a new instance.
     */
    public ServletRegistration(String servletName, Servlet servlet, DefaultServletContext servletContext,
                               UrlMapper<ServletRegistration> urlMapper) {
        this(servletName, servlet, servletContext, urlMapper, ImmutableMap.copyOf(new HashMap<>()));
    }

    /**
     * Creates a new instance.
     */
    public ServletRegistration(String servletName, Servlet servlet, DefaultServletContext servletContext,
                               UrlMapper<ServletRegistration> urlMapper, Map<String, String> initParameterMap) {
        requireNonNull(servletName, "servletName");
        requireNonNull(servlet, "servlet");
        requireNonNull(servletContext, "servletContext");
        requireNonNull(urlMapper, "urlMapper");

        this.servletName = servletName;
        this.servlet = servlet;
        this.servletContext = servletContext;
        this.urlMapper = urlMapper;
        this.initParameterMap = ImmutableMap.copyOf(initParameterMap);
        servletConfig = new ServletConfig() {
            @Override
            public String getServletName() {
                return ServletRegistration.this.servletName;
            }

            @Override
            public ServletContext getServletContext() {
                return ServletRegistration.this.servletContext;
            }

            @Override
            @Nullable
            public String getInitParameter(String name) {
                return ServletRegistration.this.getInitParameter(name);
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.enumeration(ImmutableSet.copyOf(getInitParameters().keySet()));
            }
        };
    }

    /**
     * Get servlet security element.
     */
    @Nullable
    public ServletSecurityElement getServletSecurityElement() {
        return servletSecurityElement;
    }

    /**
     * Get multipart config element.
     */
    @Nullable
    public MultipartConfigElement getMultipartConfigElement() {
        return multipartConfigElement;
    }

    /**
     * Get servlet config.
     */
    public ServletConfig getServletConfig() {
        return servletConfig;
    }

    /**
     * Get servlet.
     */
    public Servlet getServlet() {
        return servlet;
    }

    /**
     * Is async supported.
     */
    public Boolean isAsyncSupported() {
        return asyncSupported;
    }

    /**
     * Get load on startup.
     */
    public int getLoadOnStartup() {
        return loadOnStartup;
    }

    /**
     * Is initialization servlet cas.
     */
    public boolean isInitServletCas(boolean expect, boolean update) {
        return initServlet.compareAndSet(expect, update);
    }

    /**
     * Is initialization servlet.
     */
    public boolean isInitServlet() {
        return initServlet.get();
    }

    @Override
    public Set<String> addMapping(String... urlPatterns) {
        requireNonNull(urlPatterns, "urlPatterns");
        mappingSet.addAll(Arrays.asList(urlPatterns));
        for (String pattern : urlPatterns) {
            urlMapper.addMapping(pattern, this, servletName);
        }
        return mappingSet;
    }

    @Override
    public Collection<String> getMappings() {
        return mappingSet;
    }

    @Override
    @Nullable
    public String getRunAsRole() {
        return roleName;
    }

    @Override
    public String getName() {
        return servletName;
    }

    @Override
    public String getClassName() {
        return servlet.getClass().getName();
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        throw new IllegalStateException("Can't set init parameter after ServletRegistration is initialized");
    }

    @Override
    @Nullable
    public String getInitParameter(String name) {
        requireNonNull(name, "name");
        return initParameterMap.get(name);
    }

    @Override
    public Set<String> setInitParameters(Map<String, String> initParameters) {
        throw new IllegalStateException("Can't set init parameters after ServletRegistration is initialized");
    }

    @Override
    public Map<String, String> getInitParameters() {
        return initParameterMap;
    }

    @Override
    public void setLoadOnStartup(int loadOnStartup) {
        this.loadOnStartup = loadOnStartup;
    }

    @Override
    public Set<String> setServletSecurity(ServletSecurityElement constraint) {
        requireNonNull(constraint, "constraint");
        servletSecurityElement = constraint;
        return new HashSet<>(servletSecurityElement.getMethodNames());
    }

    @Override
    public void setMultipartConfig(MultipartConfigElement multipartConfig) {
        requireNonNull(multipartConfig, "multipartConfig");
        multipartConfigElement = multipartConfig;
    }

    @Override
    public void setRunAsRole(String roleName) {
        requireNonNull(roleName, "roleName");
        this.roleName = roleName;
    }

    @Override
    public void setAsyncSupported(boolean isAsyncSupported) {
        asyncSupported = isAsyncSupported;
    }
}
