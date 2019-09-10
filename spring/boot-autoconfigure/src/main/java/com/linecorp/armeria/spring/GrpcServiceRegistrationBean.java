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
 * >             .setService(new GrpcServiceBuilder()
 * >                                 .addService(new HelloService())
 * >                                 .supportedSerializationFormats(GrpcSerializationFormats.values())
 * >                                 .enableUnframedRequests(true)
 * >                                 .build())
 * >             .setDecorators(LoggingService.newDecorator())
 * >             .setExampleRequests(List.of(ExampleRequest.of(HelloServiceGrpc.SERVICE_NAME,
 * >                                                           "Hello",
 * >                                                           HelloRequest.newBuilder()
 * >                                                                       .setName("Armeria")
 * >                                                                       .build())));
 * > }
 * }</pre>
 */
public class GrpcServiceRegistrationBean
        extends AbstractServiceRegistrationBean<ServiceWithRoutes<HttpRequest, HttpResponse>,
        GrpcServiceRegistrationBean> {

    public static final class GrpcExampleRequest extends ExampleRequest {

        private GrpcExampleRequest(String serviceType, String methodName, Object exampleRequest) {
            super(serviceType, methodName, exampleRequest);
        }

        public static GrpcExampleRequest of(@NotNull String serviceType,
                                            @NotNull String methodName,
                                            @NotNull Object exampleRequest) {
            return new GrpcExampleRequest(serviceType, methodName, exampleRequest);
        }
    }

    @Deprecated
    public static class ExampleRequest {
        private final String serviceType;
        private final String methodName;
        private final Object exampleRequest;

        private ExampleRequest(String serviceType, String methodName, Object exampleRequest) {
            this.serviceType = serviceType;
            this.methodName = methodName;
            this.exampleRequest = exampleRequest;
        }

        public String getServiceType() {
            return serviceType;
        }

        public String getMethodName() {
            return methodName;
        }

        public Object getExampleRequest() {
            return exampleRequest;
        }

        public static ExampleRequest of(@NotNull String serviceType,
                                        @NotNull String methodName,
                                        @NotNull Object exampleRequest) {
            return new ExampleRequest(serviceType, methodName, exampleRequest);
        }
    }

    /**
     * Sample requests to populate debug forms in {@link DocService}.
     * This should be a list of request objects which correspond to methods
     * in this gRPC service.
     */
    @NotNull
    @Deprecated
    private Collection<ExampleRequest> exampleRequests = new ArrayList<>();

    /**
     * Returns sample requests of {@link #getService()}.
     */
    @NotNull
    @Deprecated
    public Collection<ExampleRequest> getExampleRequests() {
        return exampleRequests;
    }

    /**
     * Sets sample requests for {@link #getService()}.
     */
    @Deprecated
    public GrpcServiceRegistrationBean setExampleRequests(
            @NotNull Collection<ExampleRequest> exampleRequests) {
        this.exampleRequests = exampleRequests;
        return this;
    }
}
