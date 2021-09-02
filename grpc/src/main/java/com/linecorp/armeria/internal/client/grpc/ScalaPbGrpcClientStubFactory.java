/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.internal.client.grpc.GrpcClientFactoryUtil.newClientStubCreationException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import com.linecorp.armeria.client.grpc.GrpcClientStubFactory;

import io.grpc.Channel;
import io.grpc.ServiceDescriptor;

/**
 * A gRPC client stub factory for <a href="https://scalapb.github.io/">ScalaPB</a>.
 */
public final class ScalaPbGrpcClientStubFactory implements GrpcClientStubFactory {

    @Override
    public ServiceDescriptor findServiceDescriptor(Class<?> clientType) {
        final Class<?> stubClass = clientType.getEnclosingClass();
        if (stubClass == null) {
            return null;
        }

        try {
            final Method method = stubClass.getDeclaredMethod("SERVICE");
            return (ServiceDescriptor) method.invoke(null);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            return null;
        }
    }

    @Override
    public Object newClientStub(Class<?> clientType, Channel channel) {
        final Method stubFactoryMethod = GrpcClientFactoryUtil.findStubFactoryMethod(clientType);
        try {
            assert stubFactoryMethod != null;
            return stubFactoryMethod.invoke(null, channel);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw newClientStubCreationException(e);
        }
    }
}
