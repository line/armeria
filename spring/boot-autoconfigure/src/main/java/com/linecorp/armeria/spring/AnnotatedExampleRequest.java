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

import com.linecorp.armeria.server.docs.DocServiceBuilder;

/**
 * Used as an example request object in {@link AnnotatedServiceRegistrationBean}.
 *
 * @deprecated Use {@link DocServiceConfigurator}.
 */
@Deprecated
public final class AnnotatedExampleRequest {

    /**
     * Returns a new {@link AnnotatedExampleRequest} with the specified {@code methodName}
     * and {@code exampleRequest}.
     *
     * @deprecated Use {@link DocServiceBuilder#exampleRequestForMethod(Class, String, Object...)} and
     *             {@link DocServiceBuilder#exampleRequestForMethod(Class, String, Iterable)}.
     */
    @Deprecated
    public static AnnotatedExampleRequest of(@NotNull String methodName,
                                             @NotNull Object exampleRequest) {
        return new AnnotatedExampleRequest(methodName, exampleRequest);
    }

    private final String methodName;
    private final Object exampleRequest;

    private AnnotatedExampleRequest(String methodName, Object exampleRequest) {
        this.methodName = methodName;
        this.exampleRequest = exampleRequest;
    }

    /**
     * Returns the method name of this {@link AnnotatedExampleRequest}.
     */
    public String getMethodName() {
        return methodName;
    }

    /**
     * Returns the example request of this {@link AnnotatedExampleRequest}.
     */
    public Object getExampleRequest() {
        return exampleRequest;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("methodName", methodName)
                          .add("exampleRequest", exampleRequest)
                          .toString();
    }
}
