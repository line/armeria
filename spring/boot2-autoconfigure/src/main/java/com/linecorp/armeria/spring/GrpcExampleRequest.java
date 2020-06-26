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

import com.google.common.base.MoreObjects;

/**
 * Used as an example request object in {@link GrpcServiceRegistrationBean}.
 */
public final class GrpcExampleRequest {

    /**
     * Returns a new {@link GrpcExampleRequest} with the specified {@code serviceType}, {@code methodName}
     * and {@code exampleRequest}.
     */
    public static GrpcExampleRequest of(@NotNull String serviceType,
                                        @NotNull String methodName,
                                        @NotNull Object exampleRequest) {
        return new GrpcExampleRequest(serviceType, methodName, exampleRequest);
    }

    private final String serviceType;
    private final String methodName;
    private final Object exampleRequest;

    private GrpcExampleRequest(String serviceType, String methodName, Object exampleRequest) {
        this.serviceType = serviceType;
        this.methodName = methodName;
        this.exampleRequest = exampleRequest;
    }

    /**
     * Returns the service type of this {@link GrpcExampleRequest}.
     */
    public String getServiceType() {
        return serviceType;
    }

    /**
     * Returns the method name of this {@link GrpcExampleRequest}.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Returns the example request of this {@link GrpcExampleRequest}.
     */
    public Object getExampleRequest() {
        return exampleRequest;
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
