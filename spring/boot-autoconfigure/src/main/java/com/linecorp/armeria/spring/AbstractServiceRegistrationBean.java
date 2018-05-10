/*
 *  Copyright 2018 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.spring;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.validation.constraints.NotNull;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;

/**
 * An abstract bean with information for registering an annotated service object. It enables micrometer
 * monitoring of the service automatically.
 */
class AbstractServiceRegistrationBean<T, U> {
    /**
     * The annotated service object to register.
     */
    @NotNull
    private T service;

    /**
     * A service name to use in monitoring.
     */
    @NotNull
    private String serviceName;

    /**
     * The decorators of the annotated service object.
     */
    @NotNull
    private List<Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>>> decorators = new ArrayList<>();

    /**
     * Returns the annotated service object registered to this bean.
     */
    @NotNull
    public final T getService() {
        return service;
    }

    /**
     * Registers an annotated service object.
     */
    public final U setService(@NotNull T service) {
        this.service = service;
        return self();
    }

    /**
     * Returns this service name to use in monitoring.
     */
    @NotNull
    public final String getServiceName() {
        return serviceName;
    }

    /**
     * Sets service name to use in monitoring.
     */
    public final U setServiceName(@NotNull String serviceName) {
        this.serviceName = serviceName;
        return self();
    }

    /**
     * Returns the decorators of the annotated service object.
     */
    @NotNull
    public final List<Function<Service<HttpRequest, HttpResponse>,
            ? extends Service<HttpRequest, HttpResponse>>> getDecorators() {
        return decorators;
    }

    /**
     * Sets the decorator of the annotated service object. {@code decorator} are applied to {@code service} in
     * order.
     * @deprecated Use {@link #setDecorators(Function[])} or {@link #setDecorators(List)} instead.
     */
    @Deprecated
    public final U setDecorator(
            Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>> decorator) {
        return setDecorators(requireNonNull(decorator, "decorator"));
    }

    /**
     * Sets the decorator of the annotated service object. {@code decorators} are applied to {@code service} in
     * order.
     */
    @SafeVarargs
    public final U setDecorators(
            Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>>... decorators) {
        return setDecorators(ImmutableList.copyOf(requireNonNull(decorators, "decorators")));
    }

    /**
     * Sets the decorators of the annotated service object. {@code decorators} are applied to {@code service} in
     * order.
     */
    public final U setDecorators(
            List<Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>>> decorators) {
        this.decorators = requireNonNull(decorators, "decorators");
        return self();
    }

    @SuppressWarnings("unchecked")
    private U self() {
        return (U) this;
    }
}
