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

import java.io.IOException;

import javax.annotation.Nullable;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/**
 * Servlet request scheduling.
 */
final class ServletRequestDispatcher implements RequestDispatcher {
    private final String path;
    private final String name;
    private final ServletFilterChain filterChain;
    /**
     * Match mapping.
     */
    final UrlMapper.Element<DefaultServletRegistration> mapperElement;

    /**
     * Creates a new instance.
     */
    ServletRequestDispatcher(ServletFilterChain filterChain, String path,
                             @Nullable UrlMapper.Element<DefaultServletRegistration> element) {
        this(filterChain, path, path, element);
    }

    /**
     * Creates a new instance.
     */
    ServletRequestDispatcher(ServletFilterChain filterChain, String name) {
        this(filterChain, name, name, null);
    }

    /**
     * Creates a new instance.
     */
    ServletRequestDispatcher(ServletFilterChain filterChain, String path, String name,
                             @Nullable UrlMapper.Element<DefaultServletRegistration> element) {
        requireNonNull(filterChain, "filterChain");
        requireNonNull(path, "path");
        requireNonNull(name, "name");
        this.filterChain = filterChain;
        this.path = path;
        this.name = name;
        mapperElement = element;
    }

    /**
     * Forward to other servlets for processing (note: transfer control of the response to other servlets).
     * @param request request.
     * @param response response.
     * @throws ServletException ServletException.
     * @throws IOException IOException.
     */
    @Override
    public void forward(ServletRequest request, ServletResponse response) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * Introduction of response content from other servlets
     * (note: other servlets can write data, but cannot submit data)
     * Premise: transfer-encoding is required.
     * @param request request.
     * @param response response.
     * @throws ServletException ServletException.
     * @throws IOException IOException.
     */
    @Override
    public void include(ServletRequest request, ServletResponse response) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    /**
     * dispatch.
     * @param request request.
     * @param response response.
     * @throws ServletException ServletException.
     * @throws IOException IOException.
     */
    void dispatch(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        requireNonNull(request, "request");
        requireNonNull(response, "response");
        filterChain.doFilter(request, response);
    }

    /**
     * Get path.
     */
    String getPath() {
        return path;
    }

    /**
     * Get name.
     */
    String getName() {
        return filterChain.getServletRegistration().getName();
    }

    void clearFilter() {
        filterChain.getFilterRegistrationList().clear();
    }
}
