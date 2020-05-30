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
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterRegistration.Dynamic;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.server.servlet.util.UrlMapper;

/**
 * servlet Filter registration.
 */
public class FilterRegistration implements Dynamic {

    private final String filterName;
    private final Filter filter;
    private final UrlMapper<FilterRegistration> urlMapper;
    private final Set<String> mappingSet = new HashSet<>();
    private final Set<String> servletNameMappingSet = new HashSet<>();
    private final Map<String, String> initParameterMap;

    private boolean asyncSupported = true;

    /**
     * Creates a new instance.
     */
    public FilterRegistration(String filterName, Filter filter, UrlMapper<FilterRegistration> urlMapper) {
        this(filterName, filter, urlMapper, ImmutableMap.copyOf(new HashMap<>()));
    }

    /**
     * Creates a new instance.
     */
    public FilterRegistration(String filterName, Filter filter, UrlMapper<FilterRegistration> urlMapper,
                              Map<String, String> initParameterMap) {
        checkArgument(!isNullOrEmpty(filterName), "filterName: %s (expected: not null and empty)", filterName);
        requireNonNull(filter, "filter");
        requireNonNull(urlMapper, "urlMapper");

        this.filterName = filterName;
        this.filter = filter;
        this.urlMapper = urlMapper;
        this.initParameterMap = ImmutableMap.copyOf(initParameterMap);
    }

    /**
     * Get filter.
     */
    public Filter getFilter() {
        return filter;
    }

    @Override
    public String getName() {
        return filterName;
    }

    @Override
    public String getClassName() {
        return filter.getClass().getName();
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        return initParameterMap.put(name, value) != null;
    }

    @Override
    @Nullable
    public String getInitParameter(String name) {
        requireNonNull(name, "name");
        return initParameterMap.get(name);
    }

    @Override
    public Set<String> setInitParameters(Map<String, String> initParameters) {
        throw new IllegalStateException("Can't set init parameters after FilterRegistration is initialized");
    }

    @Override
    public Map<String, String> getInitParameters() {
        return initParameterMap;
    }

    @Override
    public void setAsyncSupported(boolean isAsyncSupported) {
        asyncSupported = isAsyncSupported;
    }

    @Override
    public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter,
                                          String... servletNames) {
        requireNonNull(dispatcherTypes, "dispatcherTypes");
        requireNonNull(servletNames, "servletNames");
        servletNameMappingSet.addAll(Arrays.asList(servletNames));
    }

    @Override
    public Collection<String> getServletNameMappings() {
        return servletNameMappingSet;
    }

    @Override
    public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter,
                                         String... urlPatterns) {
        requireNonNull(dispatcherTypes, "dispatcherTypes");
        requireNonNull(urlPatterns, "urlPatterns");
        mappingSet.addAll(Arrays.asList(urlPatterns));
        for (String pattern : urlPatterns) {
            urlMapper.addMapping(pattern, this, filterName);
        }
    }

    @Override
    public Collection<String> getUrlPatternMappings() {
        return mappingSet;
    }
}
