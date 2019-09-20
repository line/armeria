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
     * Returns a new {@link GrpcExampleHeaders} with the specified {@code serviceType}
     * and {@code exampleHttpHeaders}.
     */
    public static GrpcExampleHeaders of(@NotNull String serviceType,
                                        @NotNull HttpHeaders exampleHttpHeaders) {
        return new GrpcExampleHeaders(serviceType, exampleHttpHeaders);
    }

    private final String serviceType;
    private final HttpHeaders exampleHttpHeaders;

    private GrpcExampleHeaders(String serviceType, HttpHeaders exampleHttpHeaders) {
        this.serviceType = serviceType;
        this.exampleHttpHeaders = exampleHttpHeaders;
    }

    /**
     * Returns the service type of this {@link GrpcExampleHeaders}.
     */
    public String getServiceType() {
        return serviceType;
    }

    /**
     * Returns the example headers of this {@link GrpcExampleHeaders}.
     */
    public HttpHeaders getExampleHttpHeaders() {
        return exampleHttpHeaders;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serviceType", serviceType)
                          .add("exampleHttpHeaders", exampleHttpHeaders)
                          .toString();
    }
}
