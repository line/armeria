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

package com.linecorp.armeria.internal.client.thrift;

import static com.linecorp.armeria.common.thrift.AsyncMethodCallbacks.invokeOnError;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import org.apache.thrift.async.AsyncMethodCallback;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.thrift.THttpClient;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;

final class THttpClientInvocationHandler
        extends AbstractUnwrappable<THttpClient> implements InvocationHandler, ClientBuilderParams {

    private static final Object[] NO_ARGS = new Object[0];

    private final ClientBuilderParams params;

    THttpClientInvocationHandler(ClientBuilderParams params, THttpClient thriftClient) {
        super(thriftClient);
        this.params = params;
    }

    @Override
    public Scheme scheme() {
        return params.scheme();
    }

    @Override
    public EndpointGroup endpointGroup() {
        return params.endpointGroup();
    }

    @Override
    public String absolutePathRef() {
        return params.absolutePathRef();
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
            return params.clientType().getSimpleName() + '(' + uri().getRawPath() + ')';
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
        @Nullable
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

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final String path = uri().getRawPath();
            final String fragment = uri().getRawFragment();
            try {
                final RpcResponse reply;
                if (fragment != null) {
                    reply = unwrap().executeMultiplexed(
                            path, params.clientType(), fragment, method.getName(), args);
                } else {
                    reply = unwrap().execute(path, params.clientType(), method.getName(), args);
                }

                final ClientRequestContext ctx = captor.get();
                if (callback != null) {
                    reply.handle((res, cause) -> {
                        try (SafeCloseable ignored = ctx.push()) {
                            if (cause != null) {
                                invokeOnError(callback, cause);
                            } else {
                                callback.onComplete(res);
                            }
                        } catch (Exception e) {
                            CompletionActions.log(e);
                        }
                        return null;
                    });

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
                    @Nullable
                    final ClientRequestContext ctx = captor.getOrNull();
                    if (ctx != null) {
                        try (SafeCloseable ignored = ctx.push()) {
                            invokeOnError(callback, cause);
                        } catch (Exception e) {
                            CompletionActions.log(e);
                        }
                        return null;
                    }

                    try {
                        invokeOnError(callback, cause);
                    } catch (Exception e) {
                        CompletionActions.log(e);
                    }
                    return null;
                } else {
                    throw cause;
                }
            }
        }
    }
}
