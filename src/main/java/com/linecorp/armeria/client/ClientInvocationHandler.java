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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.util.stream.Stream;

import io.netty.util.concurrent.Future;
import io.netty.util.internal.EmptyArrays;

final class ClientInvocationHandler implements InvocationHandler {

    private static final Method OBJECT_HASHCODE = objectMethod("hashCode");
    private static final Method OBJECT_EQUALS = objectMethod("equals", Object.class);
    private static final Method OBJECT_TOSTRING = objectMethod("toString");

    private static Method objectMethod(String name, Class<?>... paramTypes) {
        try {
            return Object.class.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            throw new Error(e); // Should never happen
        }
    }

    private final URI uri;
    private final Class<?> interfaceClass;
    private final RemoteInvoker remoteInvoker;
    private final ClientCodec codec;
    private final ClientOptions options;

    ClientInvocationHandler(URI uri, Class<?> interfaceClass,
                            RemoteInvoker remoteInvoker, ClientCodec codec, ClientOptions options) {

        this.uri = uri;
        this.interfaceClass = interfaceClass;
        this.remoteInvoker = remoteInvoker;
        this.codec = codec;
        this.options = options;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            // Handle the methods in Object
            return invokeObjectMethod(proxy, method, args);
        } else {
            assert method.getDeclaringClass() == interfaceClass;
            // Handle the methods in the interface.
            return invokeClientMethod(method, args);
        }
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        if (OBJECT_TOSTRING.equals(method)) {
            return interfaceClass.getSimpleName() + '(' + uri + ')';
        }
        if (OBJECT_HASHCODE.equals(method)) {
            return System.identityHashCode(proxy);
        }
        if (OBJECT_EQUALS.equals(method)) {
            return proxy == args[0];
        }

        throw new Error("unknown method: " + method);
    }

    private Object invokeClientMethod(Method method, Object[] args) throws Throwable {
        if (args == null) {
            args = EmptyArrays.EMPTY_OBJECTS;
        }

        try {
            Future<Object> resultFuture = remoteInvoker.invoke(uri, options, codec, method, args);
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
