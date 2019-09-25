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
 * >             .setService(new GrpcServiceBuilder()
 * >                                 .addService(new HelloService())
 * >                                 .supportedSerializationFormats(GrpcSerializationFormats.values())
 * >                                 .enableUnframedRequests(true)
 * >                                 .build())
 * >             .setDecorators(LoggingService.newDecorator())
 * >             .addExampleRequest(GrpcExampleRequest.of(HelloServiceGrpc.SERVICE_NAME,
 * >                                                      "Hello",
 * >                                                      HelloRequest.newBuilder().setName("Armeria").build()))
 * >             .addExampleHeader(HttpHeaders.of("my-header", "headerVal"));
 * > }
 * }</pre>
 */
public class GrpcServiceRegistrationBean
        extends AbstractServiceRegistrationBean<ServiceWithRoutes<HttpRequest, HttpResponse>,
        GrpcServiceRegistrationBean, GrpcExampleRequest, GrpcExampleHeaders> {

    /**
     * Adds an example request for {@link #getService()}.
     */
    public GrpcServiceRegistrationBean addExampleRequest(@NotNull String serviceType,
                                                         @NotNull String methodName,
                                                         @NotNull Object exampleRequest) {
        return addExampleRequest(GrpcExampleRequest.of(serviceType, methodName, exampleRequest));
    }

    /**
     * Adds an example HTTP header.
     */
    public GrpcServiceRegistrationBean addExampleHeader(String serviceType, HttpHeaders headers) {
        return addExampleHeader(GrpcExampleHeaders.of(serviceType, headers));
    }

    /**
     * Adds an example HTTP header.
     */
    public GrpcServiceRegistrationBean addExampleHeader(String serviceType, CharSequence name, String value) {
        return addExampleHeader(GrpcExampleHeaders.of(serviceType, HttpHeaders.of(name, value)));
    }
}
