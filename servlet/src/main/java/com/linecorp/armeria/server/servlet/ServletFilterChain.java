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
import java.util.ArrayList;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

/**
 * The servlet filter chain.
 */
final class ServletFilterChain implements FilterChain {

    private final List<DefaultFilterRegistration> filterRegistrationList = new ArrayList<>();
    private final DefaultServletRegistration servletRegistration;
    private int pos;

    /**
     * Get new instance.
     */
    ServletFilterChain(DefaultServletRegistration servletRegistration) {
        requireNonNull(servletRegistration, "servletRegistration");
        this.servletRegistration = servletRegistration;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response)
            throws IOException, ServletException {
        requireNonNull(request, "request");
        requireNonNull(response, "response");

        if (pos == 0) {
            servletRegistration.getServlet().init(servletRegistration.getServletConfig());
        }

        if (pos < filterRegistrationList.size()) {
            pos++;
            filterRegistrationList.get(pos).getFilter().doFilter(request, response, this);
        } else {
            servletRegistration.getServlet().service(request, response);
        }
    }

    /**
     * Get filter registration list.
     */
    List<DefaultFilterRegistration> getFilterRegistrationList() {
        return filterRegistrationList;
    }
}
