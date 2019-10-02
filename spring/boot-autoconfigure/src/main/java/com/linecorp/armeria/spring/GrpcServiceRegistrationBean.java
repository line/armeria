/*
 * Copyright 2019 LINE Corporation
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

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceWithRoutes;

/**
 * A bean with information for registering a gRPC service.
 * It enables Micrometer metric collection of the service automatically.
 * <pre>{@code
 * > @Bean
 * > public GrpcServiceRegistrationBean helloService() {
 * >     return new GrpcServiceRegistrationBean()
 * >             .setServiceName("helloService")
 * >             .setService(GrpcService.builder()
 * >                                    .addService(new HelloService())
 * >                                    .supportedSerializationFormats(GrpcSerializationFormats.values())
 * >                                    .enableUnframedRequests(true)
 * >                                    .build())
 * >             .setDecorators(LoggingService.newDecorator())
 * >             .addExampleRequests(GrpcExampleRequest.of(
 * >                    HelloServiceGrpc.SERVICE_NAME, "Hello",
 * >                    HelloRequest.newBuilder().setName("Armeria").build()))
 * >             .addExampleHeaders(GrpcExampleHeaders.of(HelloServiceGrpc.SERVICE_NAME,
 * >                                                      HttpHeaders.of("my-header", "headerVal")));
 * > }
 * }</pre>
 */
public class GrpcServiceRegistrationBean
        extends AbstractServiceRegistrationBean<ServiceWithRoutes<HttpRequest, HttpResponse>,
        GrpcServiceRegistrationBean, GrpcExampleRequest, GrpcExampleHeaders> {

    /**
     * Adds an example request for {@link #getService()}.
     */
    public GrpcServiceRegistrationBean addExampleRequests(String serviceName, String methodName,
                                                          Object exampleRequest) {
        return addExampleRequests(GrpcExampleRequest.of(serviceName, methodName, exampleRequest));
    }

    /**
     * Adds an example HTTP header for all service methods.
     */
    public GrpcServiceRegistrationBean addExampleHeaders(String serviceName, HttpHeaders exampleHeaders) {
        return addExampleHeaders(GrpcExampleHeaders.of(serviceName, exampleHeaders));
    }

    /**
     * Adds an example HTTP header for all service methods.
     */
    public GrpcServiceRegistrationBean addExampleHeaders(String serviceName, CharSequence name, String value) {
        return addExampleHeaders(GrpcExampleHeaders.of(serviceName, name, value));
    }

    /**
     * Adds an example HTTP header for the specified method.
     */
    public GrpcServiceRegistrationBean addExampleHeaders(
            String serviceName, String methodName, CharSequence name, String value) {
        return addExampleHeaders(GrpcExampleHeaders.of(serviceName, methodName, name, value));
    }

    /**
     * Adds an example HTTP header for the specified method.
     */
    public GrpcServiceRegistrationBean addExampleHeaders(
            String serviceName, String methodName, HttpHeaders exampleHeaders) {
        return addExampleHeaders(GrpcExampleHeaders.of(serviceName, methodName, exampleHeaders));
    }

    /**
     * Adds example HTTP headers for the specified method.
     */
    public GrpcServiceRegistrationBean addExampleHeaders(
            String serviceName, String methodName, @NotNull Iterable<? extends HttpHeaders> exampleHeaders) {
        exampleHeaders.forEach(h -> addExampleHeaders(serviceName, methodName, h));
        return this;
    }

    /**
     * Adds example HTTP headers for the specified method.
     */
    public GrpcServiceRegistrationBean addExampleHeaders(
            String serviceName, String methodName, @NotNull HttpHeaders... exampleHeaders) {
        return addExampleHeaders(serviceName, methodName, ImmutableList.copyOf(exampleHeaders));
    }
}
