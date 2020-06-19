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
import static java.util.Objects.requireNonNull;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServlet;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;

/**
 * A builder class which creates a new {@link DefaultServletContext} instance.
 */
public class ServletBuilder {
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

    /**
     * Creates a new {@link ServerBuilder}.
     */
    public ServerBuilder build() {
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

    /**
     * Add a servlet.
     */
    public ServletBuilder servlet(String path, HttpServlet httpServlet) {
        requireNonNull(path, "path");
        requireNonNull(httpServlet, "httpServlet");
        validatePath(path);
        servletContext.addServlet(contextPath + path, httpServlet);
        return this;
    }

    /**
     * Add a servlet.
     */
    public ServletBuilder servlet(String path, String servletClass) {
        requireNonNull(path, "path");
        requireNonNull(servletClass, "servletClass");
        validatePath(path);
        servletContext.addServlet(contextPath + path, servletClass);
        return this;
    }

    /**
     * Validate servlet path.
     */
    public void validatePath(String path) {
        if (path.isEmpty() || "/".equals(path)) {
            rootServletAdded = true;
        } else {
            checkArgument(path.charAt(0) == '/',
                          "servletPath must start with /. servletPath: %s",
                          path);
        }
    }

    /**
     * Set attribute value.
     */
    public ServletBuilder attribute(String key, @Nullable Object value) {
        requireNonNull(key, "key");
        servletContext.setAttribute(key, value);
        return this;
    }

    /**
     * Set init parameter.
     */
    public ServletBuilder initParameter(String key, @Nullable String value) {
        requireNonNull(key, "key");
        servletContext.setInitParameter(key, value);
        return this;
    }

    /**
     * Adds a mime mapping.
     */
    public ServletBuilder mimeMapping(String extension, String mimeType) {
        servletContext.mimeMapping(extension, mimeType);
        return this;
    }

    /**
     * Adds mime mappings.
     */
    public ServletBuilder mimeMappings(Map<String, String> mappings) {
        servletContext.mimeMappings(mappings);
        return this;
    }

    /**
     * Set request character encoding.
     */
    public ServletBuilder requestEncoding(String requestEncoding) {
        requireNonNull(requestEncoding, "requestEncoding");
        servletContext.setRequestCharacterEncoding(requestEncoding);
        return this;
    }

    /**
     * Set response character encoding.
     */
    public ServletBuilder responseEncoding(String responseEncoding) {
        requireNonNull(responseEncoding, "responseEncoding");
        servletContext.setResponseCharacterEncoding(responseEncoding);
        return this;
    }
}
