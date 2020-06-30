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
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

import javax.validation.constraints.NotNull;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.server.AnnotatedServiceBindingBuilder;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceBindingBuilder;
import com.linecorp.armeria.server.docs.DocService;

/**
 * An abstract bean with information for registering a service object.
 * It enables Micrometer metric collection of the service automatically.
 *
 * @param <T> the type of the service object to be registered
 * @param <U> the type of the implementation of this bean
 * @param <V> the type of the example request object to be registered
 * @param <W> the type of the example header object to be registered
 *
 * @deprecated Use {@link ArmeriaServerConfigurator}.
 */
@Deprecated
public class AbstractServiceRegistrationBean<T, U, V, W> {
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
    private List<Function<? super HttpService, ? extends HttpService>> decorators = new ArrayList<>();

    /**
     * Example requests to populate debug forms in {@link DocService}.
     * This should be a list of request objects which correspond to methods
     * in each service.
     */
    @NotNull
    private Collection<? extends V> exampleRequests = ImmutableList.of();

    /**
     * Example {@link HttpHeaders} being used in debug forms.
     */
    @NotNull
    private Collection<? extends W> exampleHeaders = ImmutableList.of();

    /**
     * Returns the annotated service object registered to this bean.
     */
    @NotNull
    public final T getService() {
        return service;
    }

    /**
     * Registers an annotated service object.
     *
     * @deprecated Use {@link ServerBuilder#service(Route, HttpService)}}
     */
    @Deprecated
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
     *
     * @deprecated The service name is automatically set now.
     */
    @Deprecated
    public final U setServiceName(@NotNull String serviceName) {
        this.serviceName = serviceName;
        return self();
    }

    /**
     * Returns the decorators of the annotated service object.
     */
    @NotNull
    public final List<Function<? super HttpService, ? extends HttpService>> getDecorators() {
        return decorators;
    }

    /**
     * Sets the decorator of the annotated service object. {@code decorators} are applied to {@code service} in
     * order.
     *
     * @deprecated Use {@link ServiceBindingBuilder#decorator(Function)} or
     *             {@link AnnotatedServiceBindingBuilder#decorator(Function)}.
     */
    @SafeVarargs
    @Deprecated
    public final U setDecorators(
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return setDecorators(ImmutableList.copyOf(requireNonNull(decorators, "decorators")));
    }

    /**
     * Sets the decorators of the annotated service object. {@code decorators} are applied to {@code service} in
     * order.
     *
     * @deprecated Use {@link ServiceBindingBuilder#decorator(Function)} or
     *             {@link AnnotatedServiceBindingBuilder#decorator(Function)}.
     */
    @Deprecated
    public final U setDecorators(
            List<Function<? super HttpService, ? extends HttpService>> decorators) {
        this.decorators = requireNonNull(decorators, "decorators");
        return self();
    }

    /**
     * Returns example requests of {@link #getService()}.
     */
    @NotNull
    public Collection<? extends V> getExampleRequests() {
        return exampleRequests;
    }

    /**
     * Sets example requests for {@link #getService()}.
     */
    public U setExampleRequests(@NotNull Iterable<? extends V> exampleRequests) {
        this.exampleRequests = ImmutableList.copyOf(exampleRequests);
        return self();
    }

    /**
     * Sets example requests for {@link #getService()}.
     */
    public U setExampleRequests(@NotNull Collection<? extends V> exampleRequests) {
        return setExampleRequests((Iterable<? extends V>) exampleRequests);
    }

    /**
     * Sets example requests for {@link #getService()}.
     */
    @SuppressWarnings("unchecked")
    public U setExampleRequests(@NotNull V... exampleRequests) {
        return setExampleRequests(ImmutableList.copyOf(exampleRequests));
    }

    /**
     * Adds example requests for {@link #getService()}.
     */
    public U addExampleRequests(@NotNull Iterable<? extends V> exampleRequests) {
        this.exampleRequests = ImmutableList.<V>builder().addAll(this.exampleRequests)
                                                         .addAll(exampleRequests)
                                                         .build();
        return self();
    }

    /**
     * Adds example requests for {@link #getService()}.
     */
    @SuppressWarnings("unchecked")
    public U addExampleRequests(@NotNull V... exampleRequests) {
        return addExampleRequests(ImmutableList.copyOf(exampleRequests));
    }

    /**
     * Adds an example request for {@link #getService()}.
     */
    public U addExampleRequests(@NotNull V exampleRequest) {
        exampleRequests = ImmutableList.<V>builder().addAll(exampleRequests)
                                                    .add(exampleRequest)
                                                    .build();
        return self();
    }

    /**
     * Returns example HTTP headers for all service methods.
     */
    @NotNull
    public Collection<? extends W> getExampleHeaders() {
        return exampleHeaders;
    }

    /**
     * Sets example HTTP headers for all service methods.
     */
    public U setExampleHeaders(@NotNull Iterable<? extends W> exampleHeaders) {
        this.exampleHeaders = ImmutableList.copyOf(exampleHeaders);
        return self();
    }

    /**
     * Sets example HTTP headers for all service methods.
     */
    public U setExampleHeaders(@NotNull Collection<? extends W> exampleHeaders) {
        return setExampleHeaders((Iterable<? extends W>) exampleHeaders);
    }

    /**
     * Sets example HTTP headers for all service methods.
     */
    @SuppressWarnings("unchecked")
    public U setExampleHeaders(@NotNull W... exampleHeaders) {
        return setExampleHeaders(ImmutableList.copyOf(exampleHeaders));
    }

    /**
     * Adds example HTTP headers for all service methods.
     */
    public U addExampleHeaders(@NotNull Iterable<? extends W> exampleHeaders) {
        this.exampleHeaders = ImmutableList.<W>builder().addAll(this.exampleHeaders)
                                                        .addAll(exampleHeaders)
                                                        .build();
        return self();
    }

    /**
     * Adds example HTTP headers for all service methods.
     */
    @SuppressWarnings("unchecked")
    public U addExampleHeaders(@NotNull W... exampleHeaders) {
        return addExampleHeaders(ImmutableList.copyOf(exampleHeaders));
    }

    /**
     * Adds an example HTTP header for all service methods.
     */
    public U addExampleHeaders(@NotNull W exampleHeaders) {
        this.exampleHeaders = ImmutableList.<W>builder().addAll(this.exampleHeaders)
                                                        .add(exampleHeaders)
                                                        .build();
        return self();
    }

    @SuppressWarnings("unchecked")
    private U self() {
        return (U) this;
    }
}
