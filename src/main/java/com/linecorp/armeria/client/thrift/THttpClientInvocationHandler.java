/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.thrift;

import static com.linecorp.armeria.common.util.Functions.voidFunction;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import org.apache.thrift.async.AsyncMethodCallback;

import com.linecorp.armeria.client.ClientOptionDerivable;
import com.linecorp.armeria.client.ClientOptionValue;
import com.linecorp.armeria.common.thrift.ThriftReply;
import com.linecorp.armeria.common.util.CompletionActions;

final class THttpClientInvocationHandler implements InvocationHandler {

    private static final Object[] NO_ARGS = new Object[0];

    private final THttpClient thriftClient;
    private final String path;
    private final Class<?> clientType;

    THttpClientInvocationHandler(THttpClient thriftClient, String path, Class<?> clientType) {
        this.thriftClient = thriftClient;
        this.path = path;
        this.clientType = clientType;
    }

    private THttpClientInvocationHandler(THttpClientInvocationHandler handler, THttpClient thriftClient) {
        this.thriftClient = thriftClient;
        path = handler.path;
        clientType = handler.clientType;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        final Class<?> declaringClass = method.getDeclaringClass();
        if (declaringClass == Object.class) {
            // Handle the methods in Object
            return invokeObjectMethod(proxy, method, args);
        }

        if (declaringClass == ClientOptionDerivable.class) {
            return invokeClientOptionDerivableMethod(method, args);
        }

        assert declaringClass == clientType;
        // Handle the methods in the interface.
        return invokeClientMethod(method, args);
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        final String methodName = method.getName();

        switch (methodName) {
        case "toString":
            return clientType.getSimpleName() + '(' + path + ')';
        case "hashCode":
            return System.identityHashCode(proxy);
        case "equals":
            return proxy == args[0];
        default:
            throw new Error("unknown method: " + methodName);
        }
    }

    private Object invokeClientOptionDerivableMethod(Method method, Object[] args) {
        final String methodName = method.getName();
        switch (methodName) {
            case "withOptions":
                final Object arg = args[0];
                if (arg instanceof Iterable) {
                    @SuppressWarnings("unchecked")
                    final Iterable<ClientOptionValue<?>> options = (Iterable<ClientOptionValue<?>>) arg;
                    return Proxy.newProxyInstance(
                            clientType.getClassLoader(),
                            new Class<?>[] { clientType, ClientOptionDerivable.class },
                            new THttpClientInvocationHandler(this, thriftClient.withOptions(options)));
                } else if (arg instanceof ClientOptionValue[]) {
                    final ClientOptionValue<?>[] options = (ClientOptionValue<?>[]) arg;
                    return Proxy.newProxyInstance(
                            clientType.getClassLoader(),
                            new Class<?>[] { clientType, ClientOptionDerivable.class },
                            new THttpClientInvocationHandler(this, thriftClient.withOptions(options)));
                } else {
                    throw new Error("unknown argument: " + arg);
                }
            default:
                throw new Error("unknown method: " + methodName);
        }
    }

    private Object invokeClientMethod(Method method, Object[] args) throws Throwable {
        final AsyncMethodCallback<Object> callback;
        if (args == null) {
            args = NO_ARGS;
            callback = null;
        } else {
            final int lastIdx = args.length - 1;
            if (args.length > 0 && args[lastIdx] instanceof AsyncMethodCallback) {
                @SuppressWarnings("unchecked")
                final AsyncMethodCallback<Object> lastArg = (AsyncMethodCallback<Object>) args[lastIdx];
                callback = lastArg;
                args = Arrays.copyOfRange(args, 0, lastIdx);
            } else {
                callback = null;
            }
        }

        try {
            final ThriftReply reply = thriftClient.execute(path, clientType, method.getName(), args);

            if (callback != null) {
                reply.handle(voidFunction((result, cause) -> {
                    if (cause == null) {
                        callback.onComplete(result);
                    } else {
                        invokeOnError(callback, cause);
                    }
                })).exceptionally(CompletionActions::log);

                return null;
            } else {
                try {
                    return reply.get();
                } catch (ExecutionException e) {
                    throw e.getCause();
                }
            }
        } catch (Throwable cause) {
            if (callback != null) {
                invokeOnError(callback, cause);
                return null;
            } else {
                throw cause;
            }
        }
    }

    private static void invokeOnError(AsyncMethodCallback<Object> callback, Throwable cause) {
        callback.onError(cause instanceof Exception ? (Exception) cause
                                                    : new UndeclaredThrowableException(cause));
    }
}
