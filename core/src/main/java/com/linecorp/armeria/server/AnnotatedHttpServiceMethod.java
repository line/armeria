/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.Types;
import com.linecorp.armeria.server.http.dynamic.PathParam;
import com.linecorp.armeria.server.http.dynamic.ResponseConverter;

/**
 * Invokes an individual method of an annotated service. An annotated service method whose return type is not
 * {@link CompletionStage} or {@link HttpResponse} will be run in the blocking task executor.
 */
final class AnnotatedHttpServiceMethod implements BiFunction<ServiceRequestContext, HttpRequest, Object> {

    private final Object object;
    private final Method method;
    private final List<Parameter> parameters;
    private final boolean isAsynchronous;
    private final boolean aggregationRequired;

    AnnotatedHttpServiceMethod(Object object, Method method) {
        this.object = requireNonNull(object, "object");
        this.method = requireNonNull(method, "method");
        parameters = parameters(method);
        final Class<?> returnType = method.getReturnType();
        isAsynchronous = HttpResponse.class.isAssignableFrom(returnType) ||
                         CompletionStage.class.isAssignableFrom(returnType);
        aggregationRequired = parameters.stream().anyMatch(
                entry -> entry.getType().isAssignableFrom(AggregatedHttpMessage.class));
    }

    /**
     * Returns the array of {@link Parameter}, which holds the type and {@link PathParam} value.
     */
    private static List<Parameter> parameters(Method method) {
        boolean hasRequestMessage = false;
        final ImmutableList.Builder<Parameter> entries = ImmutableList.builder();
        for (java.lang.reflect.Parameter p : method.getParameters()) {
            final String name;

            PathParam pathParam = p.getAnnotation(PathParam.class);
            if (pathParam != null) {
                name = p.getAnnotation(PathParam.class).value();
            } else if (p.getType().isAssignableFrom(ServiceRequestContext.class)) {
                name = null;
            } else if (p.getType().isAssignableFrom(HttpRequest.class) ||
                       p.getType().isAssignableFrom(AggregatedHttpMessage.class)) {
                if (hasRequestMessage) {
                    throw new IllegalArgumentException("Only one request message variable is allowed.");
                }
                name = null;
                hasRequestMessage = true;
            } else {
                throw new IllegalArgumentException("Unsupported object type: " + p.getType());
            }

            entries.add(new Parameter(p.getType(), name));
        }
        return entries.build();
    }

    /**
     * Returns the set of parameter names which have an annotation of {@link PathParam}.
     */
    Set<String> pathParamNames() {
        return parameters.stream()
                         .filter(Parameter::isPathParam)
                         .map(Parameter::getName)
                         .collect(toImmutableSet());
    }

    @Override
    public Object apply(ServiceRequestContext ctx, HttpRequest req) {
        if (aggregationRequired) {
            final CompletableFuture<AggregatedHttpMessage> aggregationFuture = req.aggregate();
            if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
                return aggregationFuture.thenCompose(msg -> (CompletionStage<?>) invoke(ctx, req, msg));
            }

            if (isAsynchronous) {
                return aggregationFuture.thenApply(msg -> invoke(ctx, req, msg));
            }

            return aggregationFuture.thenApplyAsync(msg -> invoke(ctx, req, msg), ctx.blockingTaskExecutor());
        }

        if (isAsynchronous) {
            return invoke(ctx, req, null);
        }

        return CompletableFuture.supplyAsync(() -> invoke(ctx, req, null), ctx.blockingTaskExecutor());
    }

    private Object invoke(ServiceRequestContext ctx, HttpRequest req, @Nullable AggregatedHttpMessage message) {
        try (SafeCloseable ignored = RequestContext.push(ctx, false)) {
            return method.invoke(object, parameterValues(ctx, req, message));
        } catch (Exception e) {
            if (e instanceof InvocationTargetException) {
                final Throwable cause = e.getCause();
                if (cause != null) {
                    return Exceptions.throwUnsafely(cause);
                }
            }
            return Exceptions.throwUnsafely(e);
        }
    }

    /**
     * Returns array of parameters for method invocation.
     */
    private Object[] parameterValues(ServiceRequestContext ctx, HttpRequest req,
                                     @Nullable AggregatedHttpMessage message) {
        Object[] parameters = new Object[this.parameters.size()];
        for (int i = 0; i < this.parameters.size(); ++i) {
            Parameter entry = this.parameters.get(i);
            if (entry.isPathParam()) {
                String value = ctx.pathParam(entry.getName());
                assert value != null;
                parameters[i] = convertParameter(value, entry.getType());
            } else if (entry.getType().isAssignableFrom(ServiceRequestContext.class)) {
                parameters[i] = ctx;
            } else if (entry.getType().isAssignableFrom(HttpRequest.class)) {
                parameters[i] = req;
            } else if (entry.getType().isAssignableFrom(AggregatedHttpMessage.class)) {
                parameters[i] = message;
            }
        }
        return parameters;
    }

    BiFunction<ServiceRequestContext, HttpRequest, Object> withConverter(ResponseConverter converter) {
        return (ctx, req) ->
                executeSyncOrAsync(ctx, req).thenApply(obj -> convertResponse(obj, converter));
    }

    BiFunction<ServiceRequestContext, HttpRequest, Object> withConverters(
            Map<Class<?>, ResponseConverter> converters) {

        return (ctx, req) ->
                executeSyncOrAsync(ctx, req).thenApply(obj -> convertResponse(obj, converters));
    }

    private CompletionStage<?> executeSyncOrAsync(ServiceRequestContext ctx, HttpRequest req) {
        final Object ret = apply(ctx, req);
        return ret instanceof CompletionStage ?
               (CompletionStage<?>) ret : CompletableFuture.completedFuture(ret);
    }

    /**
     * Converts the given {@code str} to {@code T} type object. e.g., "42" -> 42.
     *
     * @throws IllegalArgumentException if {@code str} can't be deserialized to {@code T} type object.
     */
    @SuppressWarnings("unchecked")
    private static <T> T convertParameter(String str, Class<T> clazz) {
        try {
            if (clazz == Byte.TYPE) {
                return (T) Byte.valueOf(str);
            } else if (clazz == Short.TYPE) {
                return (T) Short.valueOf(str);
            } else if (clazz == Boolean.TYPE) {
                return (T) Boolean.valueOf(str);
            } else if (clazz == Integer.TYPE) {
                return (T) Integer.valueOf(str);
            } else if (clazz == Long.TYPE) {
                return (T) Long.valueOf(str);
            } else if (clazz == Float.TYPE) {
                return (T) Float.valueOf(str);
            } else if (clazz == Double.TYPE) {
                return (T) Double.valueOf(str);
            } else if (clazz == String.class) {
                return (T) str;
            }
        } catch (NumberFormatException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Can't convert " + str + " to type " + clazz.getSimpleName(), e);
        }

        throw new IllegalArgumentException(
                "Type " + clazz.getSimpleName() + " can't be converted.");
    }

    /**
     * Converts {@code object} into {@link HttpResponse}, using one of the given {@code converters}.
     *
     * @throws IllegalStateException if an {@link Exception} thrown during conversion
     */
    private static HttpResponse convertResponse(Object object, Map<Class<?>, ResponseConverter> converters) {
        if (object instanceof HttpResponse) {
            return (HttpResponse) object;
        } else if (object instanceof AggregatedHttpMessage) {
            return ((AggregatedHttpMessage) object).toHttpResponse();
        } else {
            ResponseConverter converter = findResponseConverter(object.getClass(), converters);
            try {
                return converter.convert(object);
            } catch (Exception e) {
                throw new IllegalStateException("Exception occurred during ResponseConverter#convert", e);
            }
        }
    }

    /**
     * Converts {@code object} into {@link HttpResponse}, using the given {@code converter}.
     *
     * @throws IllegalStateException if an {@link Exception} thrown during conversion
     */
    private static HttpResponse convertResponse(Object object, ResponseConverter converter) {
        if (object instanceof HttpResponse) {
            return (HttpResponse) object;
        } else if (object instanceof AggregatedHttpMessage) {
            return ((AggregatedHttpMessage) object).toHttpResponse();
        } else {
            try {
                return converter.convert(object);
            } catch (Exception e) {
                throw new IllegalStateException("Exception occurred during ResponseConverter#convert", e);
            }
        }
    }

    /**
     * Returns {@link ResponseConverter} instance which can convert the given {@code type} object into
     * {@link HttpResponse}, from the configured converters.
     *
     * @throws IllegalArgumentException if no appropriate {@link ResponseConverter} exists for given
     * {@code type}
     */
    private static ResponseConverter findResponseConverter(
            Class<?> type, Map<Class<?>, ResponseConverter> converters) {

        // Search for the converter mapped to itself or one of its superclasses, except Object.class.
        Class<?> current = type;
        while (current != Object.class) {
            ResponseConverter converter = converters.get(current);
            if (converter != null) {
                return converter;
            }
            current = current.getSuperclass();
        }

        // Search for the converter mapped to one of its interface.
        for (Class<?> iface : Types.getAllInterfaces(type)) {
            ResponseConverter converter = converters.get(iface);
            if (converter != null) {
                return converter;
            }
        }

        // Search for the converter mapped to Object.class.
        if (converters.containsKey(Object.class)) {
            return converters.get(Object.class);
        }

        // No appropriate converter found: raise runtime exception.
        throw new IllegalArgumentException("Converter not available for " + type.getSimpleName());
    }

    /**
     * Parameter entry, which will be used to invoke the {@link AnnotatedHttpService}.
     */
    private static final class Parameter {
        private final Class<?> type;
        private final String name;

        Parameter(Class<?> type, @Nullable String name) {
            this.type = type;
            this.name = name;
        }

        Class<?> getType() {
            return type;
        }

        @Nullable
        String getName() {
            return name;
        }

        boolean isPathParam() {
            return name != null;
        }
    }
}
