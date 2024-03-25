/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.internal.client.grpc;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.grpc.GrpcClientStubFactory;

/**
 * A wrapper exception for propagating exceptions raised during the invocation of
 * {@link GrpcClientStubFactory#findServiceDescriptor(Class)} within Armeria's built-in factories.
 * Note that this class is for internal use thus it's not exposed to users.
 */
public final class ServiceDescriptorResolutionException extends RuntimeException {

    private static final long serialVersionUID = -4062645240586772465L;
    private final String stubFactoryName;

    public ServiceDescriptorResolutionException(String stubFactoryName, Throwable cause) {
        super(requireNonNull(cause, "cause"));
        this.stubFactoryName = requireNonNull(stubFactoryName, "stubFactoryName");
    }

    @Override
    public String toString() {
        return stubFactoryName + '=' + getCause().toString();
    }
}
