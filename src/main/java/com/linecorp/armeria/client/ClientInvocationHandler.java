/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client;

import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.stream.Stream;

import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;

import io.netty.util.concurrent.Future;
import io.netty.util.internal.EmptyArrays;

// Visible to generated proxies, should not be used by client code.
public final class ClientInvocationHandler {

    private final URI uri;
    private final Class<?> interfaceClass;
    private final RemoteInvoker invoker;
    private final ClientCodec codec;
    private final ClientOptions options;

    ClientInvocationHandler(URI uri, Class<?> interfaceClass,
                            RemoteInvoker invoker, ClientCodec codec, ClientOptions options) {

        this.uri = uri;
        this.interfaceClass = interfaceClass;
        this.invoker = invoker;
        this.codec = codec;
        this.options = options;
    }

    URI uri() {
        return uri;
    }

    Class<?> interfaceClass() {
        return interfaceClass;
    }

    RemoteInvoker invoker() {
        return invoker;
    }

    ClientCodec codec() {
        return codec;
    }

    ClientOptions options() {
        return options;
    }

    @RuntimeType
    public Object invoke(@Origin Method method, @AllArguments @RuntimeType Object[] args)
            throws Throwable {
        final Class<?> declaringClass = method.getDeclaringClass();
        assert declaringClass == interfaceClass;
        // Handle the methods in the interface.
        return invokeClientMethod(method, args);
    }

    private Object invokeClientMethod(Method method, Object[] args) throws Throwable {
        if (args == null) {
            args = EmptyArrays.EMPTY_OBJECTS;
        }

        try {
            Future<Object> resultFuture = invoker.invoke(uri, options, codec, method, args);
            if (codec.isAsyncClient()) {
                return method.getReturnType().isInstance(resultFuture) ? resultFuture : null;
            } else {
                return resultFuture.sync().getNow();
            }
        } catch (Throwable cause) {
            final Throwable finalCause;
            if (cause instanceof ClosedChannelException) {
                finalCause = ClosedSessionException.INSTANCE;
            } else if (cause instanceof Error ||
                       cause instanceof RuntimeException ||
                       Stream.of(method.getExceptionTypes()).anyMatch(v -> v.isInstance(cause))) {
                finalCause = cause;
            } else {
                finalCause = new UndeclaredThrowableException(cause);
            }

            throw finalCause;
        }
    }
}
