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

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

final class ServletFilterChain implements FilterChain {

    private final DefaultServletRegistration servletRegistration;
    private boolean initialized;

    ServletFilterChain(DefaultServletRegistration servletRegistration) {
        this.servletRegistration = servletRegistration;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException {
        final Servlet servlet = servletRegistration.getServlet();
        if (initialized) {
            servlet.service(request, response);
            return;
        }

        synchronized (servlet) {
            if (!initialized) {
                initialized = true;
                servlet.init(servletRegistration.getServletConfig());
            }
        }
        servlet.service(request, response);
    }
}
