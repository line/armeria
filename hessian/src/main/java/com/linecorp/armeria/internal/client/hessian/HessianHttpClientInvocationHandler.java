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

package com.linecorp.armeria.internal.client.hessian;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.hessian.HessianClient;
import com.linecorp.armeria.client.hessian.HessianClientOptions;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.hessian.HessianMethod;
import com.linecorp.armeria.internal.common.hessian.HessianMethod.ResponseType;

final class HessianHttpClientInvocationHandler extends AbstractUnwrappable<HessianClient>
        implements InvocationHandler, ClientBuilderParams {

    private static final Object[] NO_ARGS = new Object[0];

    private final ClientBuilderParams params;

    private final Map<Class<?>, HessianServiceClientMetadata> metadataMap = new ConcurrentHashMap<>();

    HessianHttpClientInvocationHandler(ClientBuilderParams params, HessianClient hessianClient) {
        super(hessianClient);
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

    private boolean isOverloadEnabled() {
        return params.options().get(HessianClientOptions.OVERLOAD_ENABLED);
    }

    private HessianServiceClientMetadata metadata(Class<?> serviceType) {
        final HessianServiceClientMetadata metadata = metadataMap.get(serviceType);
        if (metadata != null) {
            return metadata;
        }

        return metadataMap.computeIfAbsent(serviceType,
                                           type -> new HessianServiceClientMetadata(type, isOverloadEnabled()));
    }

    @Nullable
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        @Nullable
        final String mangleName;

        mangleName = metadata(params.clientType()).mangleName(method);

        final Class<?> declaringClass = method.getDeclaringClass();
        if (mangleName == null) {
            if (declaringClass == Object.class) {
                // Handle the methods in Object
                return invokeObjectMethod(proxy, method, args);
            }
            final String methodName = method.getName();

            if ("getHessianType".equals(methodName)) {
                return proxy.getClass().getInterfaces()[0].getName();
            }
            if ("getHessianURL".equals(methodName)) {
                return uri().toString();
            }
        }
        assert mangleName != null;

        assert declaringClass == params.clientType();
        // Handle the methods in the interface.
        return invokeClientMethod(mangleName, args);
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
    private Object invokeClientMethod(String mangleName, @Nullable Object[] args) throws Throwable {
        if (args == null) {
            args = NO_ARGS;
        }
        @Nullable
        final HessianMethod func = metadata(params.clientType()).method(mangleName);
        assert func != null;
        @Nullable
        final AsyncCallback callback;

        if (func.getResponseType() == ResponseType.COMPLETION_STAGE) {
            callback = new CompletableFutureCallback();
        } else {
            callback = null;
        }

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final String path = uri().getRawPath();
            try {
                final RpcResponse reply;

                reply = unwrap().execute(path, params.clientType(), func.getName(), args);

                final ClientRequestContext ctx = captor.get();
                // async
                if (callback != null) {
                    reply.handle((res, cause) -> {
                        try (SafeCloseable ignored = ctx.push()) {
                            if (cause != null) {
                                invokeOnError(callback, Exceptions.peel(cause));
                            } else {
                                callback.onSuccess(reply.get());
                            }
                        } catch (Exception e) {
                            invokeOnError(callback, Exceptions.peel(e));
                            CompletionActions.log(e);
                        }
                        return null;
                    });

                    return callback.get();
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

    private void invokeOnError(AsyncCallback callback, Throwable throwable) {
        callback.onError(throwable);
    }

    /**
     * for async return type. we only support  CompletionStage at this moment.
     */
    interface AsyncCallback {

        void onSuccess(Object result);

        void onError(Throwable throwable);

        Object get();
    }

    static class CompletableFutureCallback implements AsyncCallback {

        CompletableFuture<Object> completableFuture = new CompletableFuture<>();

        @Override
        public void onSuccess(Object result) {
            completableFuture.complete(result);
        }

        @Override
        public void onError(Throwable throwable) {
            completableFuture.completeExceptionally(throwable);
        }

        @Override
        public Object get() {
            return completableFuture;
        }
    }
}
