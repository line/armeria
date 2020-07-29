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
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * A builder class which creates a new {@link DefaultServletContext} instance.
 */
public final class ServletBuilder {

    private final DefaultServletContext servletContext;
    private final ServerBuilder serverBuilder;
    private final String contextPath;

    private boolean rootServletAdded;

    /**
     * Creates a new instance.
     */
    public ServletBuilder(ServerBuilder serverBuilder) {
        this(serverBuilder, "");
    }

    /**
     * Creates a new instance.
     */
    public ServletBuilder(ServerBuilder serverBuilder, String contextPath) {
        requireNonNull(serverBuilder, "serverBuilder");
        requireNonNull(contextPath, "contextPath");
        if (!contextPath.isEmpty()) {
            checkArgument(contextPath.charAt(0) == '/' && contextPath.charAt(contextPath.length() - 1) != '/',
                          "contextPath must start with / and must not end with /. contextPath: %s",
                          contextPath);
        }
        servletContext = new DefaultServletContext(contextPath);
        this.contextPath = contextPath;
        this.serverBuilder = serverBuilder;
    }

    private void checkUrlPatterns(String... urlPatterns) {
        final List<String> urls = ImmutableList.copyOf(requireNonNull(urlPatterns, "urlPatterns"));
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("empty urlPatterns");
        }
        for (String url : urls) {
            if (url == null) {
                throw new IllegalArgumentException("url is null.");
            } else if (url.isEmpty() || "/".equals(url)) {
                rootServletAdded = true;
            } else if (url.charAt(0) != '/') {
                throw new IllegalArgumentException("url must start with /. url: " + url);
            }
        }
    }

    /**
     * Adds the specified {@link HttpServlet} with the specified {@code servletName} and {@code urlPatterns}.
     */
    public ServletBuilder servlet(String servletName, HttpServlet httpServlet, String... urlPatterns) {
        checkUrlPatterns(urlPatterns);
        servletContext.addServlet(servletName, httpServlet, urlPatterns);
        return this;
    }

    /**
     * Adds the specified servlet {@code className} with the specified {@code servletName}
     * and {@code urlPatterns}.
     */
    public ServletBuilder servlet(String servletName, String className, String... urlPatterns) {
        checkUrlPatterns(urlPatterns);
        servletContext.addServlet(servletName, className, urlPatterns);
        return this;
    }

    /**
     * Adds the specified {@code servletClass} with the specified {@code servletName}
     * and {@code urlPatterns}.
     */
    public ServletBuilder servlet(String servletName, Class<? extends Servlet> servletClass,
                                  String... urlPatterns) {
        checkUrlPatterns(urlPatterns);
        servletContext.addServlet(servletName, servletClass, urlPatterns);
        return this;
    }

    /**
     * Sets the specified attribute {@code value} with the mapping {@code key}.
     */
    public ServletBuilder attribute(String key, Object value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        servletContext.setAttribute(key, value);
        return this;
    }

    /**
     * Sets the initial parameter {@code value} with the mapping {@code key}.
     */
    public ServletBuilder initParameter(String key, String value) {
        requireNonNull(key, "key");
        requireNonNull(value, "value");
        servletContext.setInitParameter(key, value);
        return this;
    }

    /**
     * Sets the specified {@code mimeType} with the mapping {@code extension}.
     */
    public ServletBuilder mimeMapping(String extension, String mimeType) {
        servletContext.mimeMapping(extension, mimeType);
        return this;
    }

    /**
     * Sets the specified mime {@code mappings}.
     */
    public ServletBuilder mimeMappings(Map<String, String> mappings) {
        servletContext.mimeMappings(mappings);
        return this;
    }

    /**
     * Sets the specified request character encoding.
     */
    public ServletBuilder requestCharacterEncoding(String requestCharacterEncoding) {
        requireNonNull(requestCharacterEncoding, "requestCharacterEncoding");
        servletContext.setRequestCharacterEncoding(requestCharacterEncoding);
        return this;
    }

    /**
     * Sets the specified response character encoding.
     */
    public ServletBuilder responseCharacterEncoding(String responseCharacterEncoding) {
        requireNonNull(responseCharacterEncoding, "responseCharacterEncoding");
        servletContext.setResponseCharacterEncoding(responseCharacterEncoding);
        return this;
    }

    /**
     * Builds the servlet service based on the properties set so far and returns the {@link ServerBuilder}
     * which is specified when this {@link ServletBuilder} is created.
     */
    public ServerBuilder build() {
        checkState(!servletContext.getServletRegistrations().isEmpty(),
                   "must set at least one servlet");
        final String path = contextPath.isEmpty() ? "/" : contextPath;
        final DefaultServletService servletService = new DefaultServletService(servletContext);
        serverBuilder.serviceUnder(path, servletService);
        if (rootServletAdded) {
            serverBuilder.service(path, servletService);
        } else {
            serverBuilder.service(path, (ctx, req) -> HttpResponse.of(HttpStatus.NOT_FOUND));
        }
        servletContext.init();
        return serverBuilder;
    }
}
