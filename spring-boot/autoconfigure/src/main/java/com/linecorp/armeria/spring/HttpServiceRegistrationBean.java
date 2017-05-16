/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.spring;

import javax.annotation.Nonnull;

import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.Service;

/**
 * A bean with information for registering a http service. It enables dropwizard
 * monitoring of the service automatically.
 * <pre>{@code
 * {@literal @}Bean
 *     public HttpServiceRegistrationBean okService() {
 *         return new HttpServiceRegistrationBean()
 *             .setServiceName("okService")
 *             .setService(new OkService())
 *             .setPathMapping(PathMapping.ofExact("/ok"));
 *     }
 * }</pre>
 */
public class HttpServiceRegistrationBean {

    /**
     * The http service to register.
     */
    @Nonnull
    private Service<?, ?> service;

    /**
     * The pathMapping for the http service. For example, {@code PathMapping.ofPrefix("/foobar")}.
     */
    @Nonnull
    private PathMapping pathMapping;

    /**
     * A service name to use in monitoring.
     */
    @Nonnull
    private String serviceName;

    /**
     * Returns the http {@link Service} registered to this bean.
     */
    @Nonnull
    public Service<?, ?> getService() {
        return service;
    }

    /**
     * Register a http {@link Service}.
     */
    public HttpServiceRegistrationBean setService(@Nonnull Service<?, ?> service) {
        this.service = service;
        return this;
    }

    /**
     * Returns the {@link PathMapping} that this service map to.
     */
    public PathMapping getPathMapping() {
        return pathMapping;
    }

    /**
     * Sets a {@link PathMapping} that this service map to.
     */
    public HttpServiceRegistrationBean setPathMapping(PathMapping pathMapping) {
        this.pathMapping = pathMapping;
        return this;
    }

    /**
     * Returns this service name to use in monitoring.
     */
    @Nonnull
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Sets service name to use in monitoring.
     */
    public HttpServiceRegistrationBean setServiceName(@Nonnull String serviceName) {
        this.serviceName = serviceName;
        return this;
    }
}
