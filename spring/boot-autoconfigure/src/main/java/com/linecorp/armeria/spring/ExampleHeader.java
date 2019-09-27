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
 * Used as an example header object in {@link AnnotatedServiceRegistrationBean}
 * and {@link ThriftServiceRegistrationBean}.
 */
public final class ExampleHeader {

    /**
     * Returns a new {@link ExampleHeader} for the method with the specified {@code methodName}
     * and {@code exampleHeaders}.
     */
    public static ExampleHeader of(@NotNull String methodName, @NotNull HttpHeaders exampleHeaders) {
        return new ExampleHeader(methodName, exampleHeaders);
    }

    /**
     * Returns a new {@link ExampleHeader} for the method with the specified {@code methodName}, {@code name}
     * and {@code value}.
     */
    public static ExampleHeader of(@NotNull String methodName, @NotNull CharSequence name,
                                   @NotNull String value) {
        return of(methodName, HttpHeaders.of(name, value));
    }

    /**
     * Returns a new {@link ExampleHeader} with the specified {@code serviceType}
     * and {@code exampleHeaders}.
     */
    public static ExampleHeader of(@NotNull HttpHeaders exampleHeaders) {
        return new ExampleHeader("", exampleHeaders);
    }

    /**
     * Returns a new {@link ExampleHeader} with the specified {@code name} and {@code value}.
     */
    public static ExampleHeader of(@NotNull CharSequence name, @NotNull String value) {
        return of(HttpHeaders.of(name, value));
    }

    private final String methodName;
    private final HttpHeaders exampleHeaders;

    private ExampleHeader(String methodName, HttpHeaders exampleHeaders) {
        this.methodName = methodName;
        this.exampleHeaders = exampleHeaders;
    }

    /**
     * Returns the method name of this {@link ExampleHeader}.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Returns the example headers of this {@link ExampleHeader}.
     */
    public HttpHeaders getExampleHeaders() {
        return exampleHeaders;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("methodName", methodName)
                          .add("exampleHeaders", exampleHeaders)
                          .toString();
    }
}
