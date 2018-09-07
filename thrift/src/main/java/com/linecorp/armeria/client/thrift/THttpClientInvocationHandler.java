/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.thrift;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

import org.apache.thrift.async.AsyncMethodCallback;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.thrift.AsyncMethodCallbacks;
import com.linecorp.armeria.common.util.Exceptions;

final class THttpClientInvocationHandler implements InvocationHandler, ClientBuilderParams {

    private static final Object[] NO_ARGS = new Object[0];

    private final ClientBuilderParams params;
    private final THttpClient thriftClient;
    private final String path;
    @Nullable
    private final String fragment;

    THttpClientInvocationHandler(ClientBuilderParams params,
                                 THttpClient thriftClient, String path, @Nullable String fragment) {
        this.params = params;
        this.thriftClient = thriftClient;
        this.path = path;
        this.fragment = fragment;
    }

    @Override
    public ClientFactory factory() {
        return params.factory();
    }

    @Override
    public URI uri() {
        return params.uri();
    }

    @Override
    public Class<?> clientType() {
        return params.clientType();
    }

    @Override
    public ClientOptions options() {
        return params.options();
    }

    @Nullable
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        final Class<?> declaringClass = method.getDeclaringClass();
        if (declaringClass == Object.class) {
            // Handle the methods in Object
            return invokeObjectMethod(proxy, method, args);
        }

        assert declaringClass == params.clientType();
        // Handle the methods in the interface.
        return invokeClientMethod(method, args);
    }

    private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
        final String methodName = method.getName();

        switch (methodName) {
        case "toString":
            return params.clientType().getSimpleName() + '(' + path + ')';
        case "hashCode":
            return System.identityHashCode(proxy);
        case "equals":
            return proxy == args[0];
        default:
            throw new Error("unknown method: " + methodName);
        }
    }

    @Nullable
    private Object invokeClientMethod(Method method, @Nullable Object[] args) throws Throwable {
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
            final RpcResponse reply;
            if (fragment != null) {
                reply = thriftClient.executeMultiplexed(
                        path, params.clientType(), fragment, method.getName(), args);
            } else {
                reply = thriftClient.execute(path, params.clientType(), method.getName(), args);
            }

            if (callback != null) {
                AsyncMethodCallbacks.transfer(reply, callback);
                return null;
            } else {
                try {
                    return reply.get();
                } catch (ExecutionException e) {
                    throw Exceptions.peel(e);
                }
            }
        } catch (Throwable cause) {
            if (callback != null) {
                AsyncMethodCallbacks.invokeOnError(callback, cause);
                return null;
            } else {
                throw cause;
            }
        }
    }
}
