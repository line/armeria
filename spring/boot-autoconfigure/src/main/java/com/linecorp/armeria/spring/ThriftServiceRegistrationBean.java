/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.spring;

import javax.validation.constraints.NotNull;

import org.apache.thrift.TBase;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceBindingBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceBuilder;

/**
 * A bean with information for registering a thrift service. Enables micrometer
 * monitoring of the service and registers sample requests for use in {@link DocService}.
 * <pre>{@code
 * > @Bean
 * > public ThriftServiceRegistrationBean okService() {
 * >     return new ThriftServiceRegistrationBean()
 * >             .setServiceName("myThriftService")
 * >             .setPath("/my_service")
 * >             .setService(new MyThriftService())
 * >             .setDecorators(LoggingService.newDecorator())
 * >             .addExampleRequests(new MyThriftService.hello_args("Armeria"))
 * >             .addExampleHeaders(ExampleHeaders.of(AUTHORIZATION, "bearer b03c4fed1a"));
 * > }
 * }</pre>
 *
 * @deprecated Use {@link ArmeriaServerConfigurator} and {@link DocServiceConfigurator}.
 *             <pre>{@code
 *             > @Bean
 *             > public ArmeriaServerConfigurator myService() {
 *             >     return server -> {
 *             >         server.route()
 *             >               .path("/my_service")
 *             >               .decorator(LoggingService.newDecorator())
 *             >               .build(THttpService.of(new MyThriftService()));
 *             >     };
 *             > }
 *
 *             > @Bean
 *             > public DocServiceConfigurator myServiceDoc() {
 *             >     return docService -> {
 *             >         docService.exampleRequest(new MyThriftService.hello_args("Armeria"))
 *             >                   .exampleHttpHeaders(HttpHeaders.of(AUTHORIZATION, "bearer b03c4fed1a"));
 *             >     };
 *             }}</pre>
 */
@Deprecated
public class ThriftServiceRegistrationBean extends AbstractServiceRegistrationBean<
        HttpService, ThriftServiceRegistrationBean, TBase<?, ?>, ExampleHeaders> {

    /**
     * The url path to register the service at. If not specified, defaults to {@code /api}.
     */
    @NotNull
    private String path = "/api";

    /**
     * Returns the url path this service map to.
     */
    @NotNull
    public String getPath() {
        return path;
    }

    /**
     * Register the url path this service map to.
     *
     * @deprecated Use {@link ServiceBindingBuilder#pathPrefix(String)}
     */
    @Deprecated
    public ThriftServiceRegistrationBean setPath(@NotNull String path) {
        this.path = path;
        return this;
    }

    /**
     * Adds an example HTTP header for all service methods.
     *
     * @deprecated Use {@link DocServiceBuilder#exampleHttpHeaders(Class, HttpHeaders...)} or
     *             {@link DocServiceBuilder#exampleHttpHeaders(Class, Iterable)}.
     */
    @Deprecated
    public ThriftServiceRegistrationBean addExampleHeaders(CharSequence name, String value) {
        return addExampleHeaders(ExampleHeaders.of(HttpHeaders.of(name, value)));
    }

    /**
     * Adds an example HTTP header for the specified method.
     *
     * @deprecated Use {@link DocServiceBuilder#exampleHttpHeaders(Class, String, HttpHeaders...)} or
     *             {@link DocServiceBuilder#exampleHttpHeaders(Class, String, Iterable)}.
     */
    @Deprecated
    public ThriftServiceRegistrationBean addExampleHeaders(String methodName, HttpHeaders exampleHeaders) {
        return addExampleHeaders(ExampleHeaders.of(methodName, exampleHeaders));
    }

    /**
     * Adds an example HTTP header for the specified method.
     *
     * @deprecated Use {@link DocServiceBuilder#exampleHttpHeaders(Class, String, HttpHeaders...)} or
     *             {@link DocServiceBuilder#exampleHttpHeaders(Class, String, Iterable)}.
     */
    @Deprecated
    public ThriftServiceRegistrationBean addExampleHeaders(String methodName, CharSequence name, String value) {
        return addExampleHeaders(ExampleHeaders.of(methodName, HttpHeaders.of(name, value)));
    }

    /**
     * Adds example HTTP headers for the specified method.
     *
     * @deprecated Use {@link DocServiceBuilder#exampleHttpHeaders(Class, String, Iterable)}.
     */
    @Deprecated
    public ThriftServiceRegistrationBean addExampleHeaders(
            String methodName, @NotNull Iterable<? extends HttpHeaders> exampleHeaders) {
        exampleHeaders.forEach(h -> addExampleHeaders(methodName, h));
        return this;
    }

    /**
     * Adds example HTTP headers for the specified method.
     *
     * @deprecated Use {@link DocServiceBuilder#exampleHttpHeaders(Class, String, HttpHeaders...)}.
     */
    @Deprecated
    public ThriftServiceRegistrationBean addExampleHeaders(String methodName,
                                                           @NotNull HttpHeaders... exampleHeaders) {
        return addExampleHeaders(methodName, ImmutableList.copyOf(exampleHeaders));
    }
}
