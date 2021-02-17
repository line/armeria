/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.resteasy;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import javax.annotation.Nullable;

import org.jboss.resteasy.plugins.server.embedded.SecurityDomain;
import org.jboss.resteasy.spi.ResteasyDeployment;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Builds {@link ResteasyService}.
 * @param <T> the type of the target custom context class
 */
@UnstableApi
public final class ResteasyServiceBuilder<T> {

    private static final int DEFAULT_MAX_REQUEST_BUFFER_SIZE = 8192;
    private static final int DEFAULT_RESPONSE_BUFFER_SIZE = 4096;

    private final ResteasyDeployment deployment;
    private String contextPath = "/";
    @Nullable
    private SecurityDomain securityDomain;
    @Nullable
    private Class<T> contextClass;
    @Nullable
    private Function<ServiceRequestContext, T> contextConverter;
    private int maxRequestBufferSize = DEFAULT_MAX_REQUEST_BUFFER_SIZE;
    private int responseBufferSize = DEFAULT_RESPONSE_BUFFER_SIZE;

    ResteasyServiceBuilder(ResteasyDeployment deployment) {
        this.deployment = requireNonNull(deployment, "deployment");
    }

    /**
     * Sets the context path for {@link ResteasyService}.
     */
    public ResteasyServiceBuilder<T> path(String contextPath) {
        this.contextPath = requireNonNull(contextPath, "contextPath");
        if (contextPath.isEmpty()) {
            this.contextPath = "/";
        } else if (!contextPath.startsWith("/")) {
            this.contextPath = '/' + contextPath;
        } else {
            this.contextPath = contextPath;
        }
        return this;
    }

    /**
     * Sets the {@link SecurityDomain} for {@link ResteasyService}.
     */
    public ResteasyServiceBuilder<T> securityDomain(SecurityDomain securityDomain) {
        this.securityDomain = requireNonNull(securityDomain, "securityDomain");
        return this;
    }

    /**
     * Defines an optional converter that converts Armeria {@link ServiceRequestContext} to a target class.
     * This could be useful to expose {@link ServiceRequestContext} via custom interface as part of JAX-RS API.
     * The custom context interface is to be used with JAX-RS {@link javax.ws.rs.core.Context} annotation.
     * @param contextClass the target custom context class
     * @param contextConverter the function that adopts {@link ServiceRequestContext} to {@code contextClass}
     */
    public ResteasyServiceBuilder<T> requestContextConverter(
            Class<T> contextClass, Function<ServiceRequestContext, T> contextConverter) {
        this.contextClass = requireNonNull(contextClass, "contextClass");
        this.contextConverter = requireNonNull(contextConverter, "contextConverter");
        return this;
    }

    /**
     * Sets the maximum limit for request buffer. If the {@code Content-Length} of the request exceeds this
     * limit or the request does not include {@code Content-Length}, the request will be handled as unbuffered
     * (streaming) request.
     */
    public void maxRequestBufferSize(int maxRequestBufferSize) {
        checkArgument(maxRequestBufferSize >= 0,
                      "maxRequestBufferSize: %s (expected: >= 0)", maxRequestBufferSize);
        this.maxRequestBufferSize = maxRequestBufferSize;
    }

    /**
     * Sets the size of the response buffer to handle response content. If the response content exceeds this
     * limit the response will be handled as unbuffered (streaming) response.
     */
    public void responseBufferSize(int responseBufferSize) {
        checkArgument(responseBufferSize > 0,
                      "responseBufferSize: %s (expected: > 0)", responseBufferSize);
        this.responseBufferSize = responseBufferSize;
    }

    /**
     * Builds new {@link ResteasyService}.
     */
    public ResteasyService<T> build() {
        return new ResteasyService<>(deployment, contextPath, securityDomain, contextClass, contextConverter,
                                   maxRequestBufferSize, responseBufferSize);
    }
}
