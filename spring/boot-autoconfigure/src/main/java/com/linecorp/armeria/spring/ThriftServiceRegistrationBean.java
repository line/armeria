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

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.docs.DocService;

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
 * >             .addExampleRequest(new MyThriftService.hello_args("Armeria"))
 * >             .addExampleHeader(HttpHeaders.of(AUTHORIZATION, "bearer b03c4fed1a"));
 * > }
 * }</pre>
 */
public class ThriftServiceRegistrationBean
        extends AbstractServiceRegistrationBean<Service<HttpRequest, HttpResponse>,
        ThriftServiceRegistrationBean, TBase<?, ?>, HttpHeaders> {

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
     */
    public ThriftServiceRegistrationBean setPath(@NotNull String path) {
        this.path = path;
        return this;
    }

    /**
     * Adds an example HTTP header.
     */
    public ThriftServiceRegistrationBean addExampleHeader(CharSequence name, String value) {
        return addExampleHeader(HttpHeaders.of(name, value));
    }
}
