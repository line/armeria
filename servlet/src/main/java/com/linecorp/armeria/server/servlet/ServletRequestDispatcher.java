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

final class ServletRequestDispatcher implements RequestDispatcher {
    private final String path;
    private final String name;
    private final ServletFilterChain filterChain;

    final UrlMapper.Element<DefaultServletRegistration> mapperElement;

    ServletRequestDispatcher(ServletFilterChain filterChain, String path,
                             @Nullable UrlMapper.Element<DefaultServletRegistration> element) {
        this(filterChain, path, path, element);
    }

    ServletRequestDispatcher(ServletFilterChain filterChain, String name) {
        this(filterChain, name, name, null);
    }

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

    @Override
    public void forward(ServletRequest request, ServletResponse response) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void include(ServletRequest request, ServletResponse response) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    void dispatch(ServletRequest request, ServletResponse response)
            throws ServletException, IOException {
        requireNonNull(request, "request");
        requireNonNull(response, "response");
        filterChain.doFilter(request, response);
    }

    String getPath() {
        return path;
    }

    String getName() {
        return name;
    }
}
