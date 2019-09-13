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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collection;

import javax.validation.constraints.NotNull;

import com.google.common.base.MoreObjects;

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
 * >             .setExampleRequests(List.of(GrpcExampleRequest.of(HelloServiceGrpc.SERVICE_NAME,
 * >                                                               "Hello",
 * >                                                               HelloRequest.newBuilder()
 * >                                                                           .setName("Armeria")
 * >                                                       s                    .build())));
 * > }
 * }</pre>
 */
public class GrpcServiceRegistrationBean
        extends AbstractServiceRegistrationBean<ServiceWithRoutes<HttpRequest, HttpResponse>,
        GrpcServiceRegistrationBean> {

    public static final class GrpcExampleRequest {

        private final String serviceType;
        private final String methodName;
        private final Object exampleRequest;

        private GrpcExampleRequest(String serviceType, String methodName, Object exampleRequest) {
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

        public static GrpcExampleRequest of(@NotNull String serviceType,
                                            @NotNull String methodName,
                                            @NotNull Object exampleRequest) {
            return new GrpcExampleRequest(serviceType, methodName, exampleRequest);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("serviceType", serviceType)
                              .add("methodName", methodName)
                              .add("exampleRequest", exampleRequest)
                              .toString();
        }
    }

    /**
     * Sample requests to populate debug forms in {@link DocService}.
     * This should be a list of request objects which correspond to methods
     * in this gRPC service.
     */
    @NotNull
    private Collection<GrpcExampleRequest> exampleRequests = new ArrayList<>();

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
        this.exampleRequests = exampleRequests;
        return this;
    }

    /**
     * Adds sample request for {@link #getService()}.
     */
    public GrpcServiceRegistrationBean addGrpcExampleRequest(String serviceType, String methodName,
                                                             Object exampleRequest) {
        requireNonNull(serviceType, "serviceType");
        checkArgument(!serviceType.isEmpty(), "serviceType is empty.");
        requireNonNull(methodName, "methodName");
        checkArgument(!methodName.isEmpty(), "methodName is empty.");
        requireNonNull(exampleRequest, "exampleRequest");
        exampleRequests.add(GrpcExampleRequest.of(serviceType, methodName, exampleRequest));
        return this;
    }
}
