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
public final class GrpcExampleHeader {

    /**
     * Returns a new {@link GrpcExampleHeader} for the method with the specified {@code serviceType},
     * {@code methodName} and {@code exampleHeaders}.
     */
    public static GrpcExampleHeader of(@NotNull String serviceType, @NotNull String methodName,
                                       @NotNull HttpHeaders exampleHeaders) {
        return new GrpcExampleHeader(serviceType, methodName, exampleHeaders);
    }

    /**
     * Returns a new {@link GrpcExampleHeader} for the method with the specified {@code serviceType},
     * {@code methodName}, {@code name} and {@code value}.
     */
    public static GrpcExampleHeader of(@NotNull String serviceType, @NotNull String methodName,
                                       @NotNull CharSequence name, @NotNull String value) {
        return of(serviceType, methodName, HttpHeaders.of(name, value));
    }

    /**
     * Returns a new {@link GrpcExampleHeader} with the specified {@code serviceType}
     * and {@code exampleHeaders}.
     */
    public static GrpcExampleHeader of(@NotNull String serviceType, @NotNull HttpHeaders exampleHeaders) {
        return new GrpcExampleHeader(serviceType, "", exampleHeaders);
    }

    /**
     * Returns a new {@link GrpcExampleHeader} with the specified {@code serviceType}, {@code name}
     * and {@code value}.
     */
    public static GrpcExampleHeader of(@NotNull String serviceType, @NotNull CharSequence name,
                                       @NotNull String value) {
        return of(serviceType, "", HttpHeaders.of(name, value));
    }

    private final String serviceType;
    private final String methodName;
    private final HttpHeaders exampleHeaders;

    private GrpcExampleHeader(String serviceType, String methodName, HttpHeaders exampleHeaders) {
        this.serviceType = serviceType;
        this.methodName = methodName;
        this.exampleHeaders = exampleHeaders;
    }

    /**
     * Returns the service type of this {@link GrpcExampleHeader}.
     */
    public String getServiceType() {
        return serviceType;
    }

    /**
     * Returns the method name of this {@link GrpcExampleHeader}.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Returns the example headers of this {@link GrpcExampleHeader}.
     */
    public HttpHeaders getExampleHeaders() {
        return exampleHeaders;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serviceType", serviceType)
                          .add("methodName", methodName)
                          .add("exampleHeaders", exampleHeaders)
                          .toString();
    }
}
