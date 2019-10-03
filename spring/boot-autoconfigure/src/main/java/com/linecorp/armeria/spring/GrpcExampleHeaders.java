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

import com.linecorp.armeria.common.HttpHeaders;

/**
 * Used as an example header object in {@link GrpcServiceRegistrationBean}.
 */
public final class GrpcExampleHeaders {

    /**
     * Returns a new {@link GrpcExampleHeaders} for the method with the specified {@code serviceType},
     * {@code methodName} and {@code headers}.
     */
    public static GrpcExampleHeaders of(@NotNull String serviceType, @NotNull String methodName,
                                        @NotNull HttpHeaders headers) {
        return new GrpcExampleHeaders(serviceType, methodName, headers);
    }

    /**
     * Returns a new {@link GrpcExampleHeaders} for the method with the specified {@code serviceType},
     * {@code methodName}, {@code name} and {@code value}.
     */
    public static GrpcExampleHeaders of(@NotNull String serviceType, @NotNull String methodName,
                                        @NotNull CharSequence name, @NotNull String value) {
        return of(serviceType, methodName, HttpHeaders.of(name, value));
    }

    /**
     * Returns a new {@link GrpcExampleHeaders} with the specified {@code serviceType}
     * and {@code headers}.
     */
    public static GrpcExampleHeaders of(@NotNull String serviceType, @NotNull HttpHeaders headers) {
        return new GrpcExampleHeaders(serviceType, "", headers);
    }

    /**
     * Returns a new {@link GrpcExampleHeaders} with the specified {@code serviceType}, {@code name}
     * and {@code value}.
     */
    public static GrpcExampleHeaders of(@NotNull String serviceType, @NotNull CharSequence name,
                                        @NotNull String value) {
        return of(serviceType, "", HttpHeaders.of(name, value));
    }

    private final String serviceType;
    private final String methodName;
    private final HttpHeaders headers;

    private GrpcExampleHeaders(String serviceType, String methodName, HttpHeaders headers) {
        this.serviceType = serviceType;
        this.methodName = methodName;
        this.headers = headers;
    }

    /**
     * Returns the service type of this {@link GrpcExampleHeaders}.
     */
    public String getServiceType() {
        return serviceType;
    }

    /**
     * Returns the method name of this {@link GrpcExampleHeaders}.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Returns the example headers of this {@link GrpcExampleHeaders}.
     */
    public HttpHeaders getHeaders() {
        return headers;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serviceType", serviceType)
                          .add("methodName", methodName)
                          .add("headers", headers)
                          .toString();
    }
}
