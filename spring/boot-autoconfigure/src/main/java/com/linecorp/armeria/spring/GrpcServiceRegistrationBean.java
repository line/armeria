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

import java.util.ArrayList;
import java.util.Collection;

import javax.validation.constraints.NotNull;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceWithRoutes;
import com.linecorp.armeria.server.docs.DocService;

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
 * >             .setExampleRequests(List.of(GrpcExampleRequest.of(HelloServiceGrpc.SERVICE_NAME,
 * >                                                               "Hello",
 * >                                                               HelloRequest.newBuilder()
 * >                                                                           .setName("Armeria")
 * >                                                                           .build())));
 * > }
 * }</pre>
 */
public class GrpcServiceRegistrationBean
        extends AbstractServiceRegistrationBean<ServiceWithRoutes<HttpRequest, HttpResponse>,
        GrpcServiceRegistrationBean> {

    /**
     * Sample requests to populate debug forms in {@link DocService}.
     * This should be a list of request objects which correspond to methods
     * in this gRPC service.
     */
    @NotNull
    private final Collection<GrpcExampleRequest> exampleRequests = new ArrayList<>();

    /**
     * Returns sample requests of {@link #getService()}.
     */
    @NotNull
    public Collection<GrpcExampleRequest> getExampleRequests() {
        return exampleRequests;
    }

    /**
     * Sets sample requests for {@link #getService()}.
     */
    public GrpcServiceRegistrationBean setExampleRequests(
            @NotNull Collection<GrpcExampleRequest> exampleRequests) {
        this.exampleRequests.addAll(exampleRequests);
        return this;
    }

    /**
     * Adds a sample request for {@link #getService()}.
     */
    public GrpcServiceRegistrationBean addExampleRequest(@NotNull GrpcExampleRequest exampleRequest) {
        exampleRequests.add(exampleRequest);
        return this;
    }

    /**
     * Adds a sample request for {@link #getService()}.
     */
    public GrpcServiceRegistrationBean addExampleRequest(@NotNull String serviceType,
                                                         @NotNull String methodName,
                                                         @NotNull Object exampleRequest) {
        return addExampleRequest(GrpcExampleRequest.of(serviceType, methodName, exampleRequest));
    }
}
