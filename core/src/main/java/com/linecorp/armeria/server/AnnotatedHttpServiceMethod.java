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
import static com.linecorp.armeria.common.HttpParameters.EMPTY_PARAMETERS;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.DefaultValues;
import com.linecorp.armeria.internal.Types;
import com.linecorp.armeria.server.annotation.Optional;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.ResponseConverter;

import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.QueryStringDecoder;

/**
 * Invokes an individual method of an annotated service. An annotated service method whose return type is not
 * {@link CompletionStage} or {@link HttpResponse} will be run in the blocking task executor.
 */
final class AnnotatedHttpServiceMethod implements BiFunction<ServiceRequestContext, HttpRequest, Object> {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedHttpServiceMethod.class);

    private final Object object;
    private final Method method;
    private final List<Parameter> parameters;
    private final boolean isAsynchronous;
    private final AggregationStrategy aggregationStrategy;

    AnnotatedHttpServiceMethod(Object object, Method method, PathMapping pathMapping) {
        this.object = requireNonNull(object, "object");
        this.method = requireNonNull(method, "method");
        requireNonNull(pathMapping, "pathMapping");

        parameters = parameters(method, pathMapping.paramNames());
        final Class<?> returnType = method.getReturnType();
        isAsynchronous = HttpResponse.class.isAssignableFrom(returnType) ||
                         CompletionStage.class.isAssignableFrom(returnType);
        aggregationStrategy = AggregationStrategy.resolve(parameters);
    }

    /**
     * Returns the set of parameter names which have an annotation of {@link Param}.
     */
    Set<String> pathParamNames() {
        return parameters.stream()
                         .filter(Parameter::isPathParam)
                         .map(Parameter::name)
                         .collect(toImmutableSet());
    }

    @Override
    public Object apply(ServiceRequestContext ctx, HttpRequest req) {
        if (AggregationStrategy.aggregationRequired(aggregationStrategy, req)) {
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

    private Object invoke(ServiceRequestContext ctx, HttpRequest req, @Nullable AggregatedHttpMessage message) {
        try (SafeCloseable ignored = RequestContext.push(ctx, false)) {
            return method.invoke(object, parameterValues(ctx, req, parameters, message));
        } catch (IllegalArgumentException e) {
            // Return "400 Bad Request" if the request has not sufficient arguments or has an invalid argument.
            return HttpResponse.of(HttpStatus.BAD_REQUEST);
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
     * Returns the array of {@link Parameter}, which holds the type and {@link Param} value.
     */
    private static List<Parameter> parameters(Method method, final Set<String> pathParams) {
        requireNonNull(pathParams, "pathParams");
        boolean hasRequestMessage = false;
        final ImmutableList.Builder<Parameter> entries = ImmutableList.builder();
        for (java.lang.reflect.Parameter p : method.getParameters()) {
            final Param param = p.getAnnotation(Param.class);
            if (param != null) {
                final Optional optional = p.getAnnotation(Optional.class);
                if (pathParams.contains(param.value())) {
                    // Path variable
                    if (optional != null) {
                        throw new IllegalArgumentException(
                                "Path variable '" + param.value() + "' should not have @Optional annotation");
                    }
                    entries.add(Parameter.ofPathParam(p.getType(), param.value()));
                } else {
                    // Query string parameter or form data parameter
                    final boolean required = optional == null;
                    final String defaultValue;
                    if (required) {
                        defaultValue = null;
                    } else {
                        final String v = optional.value();
                        // Set the default value to null if it was not specified.
                        // The default value might also be specified as null value by a user.
                        defaultValue = DefaultValues.isSpecified(v) ? v : null;
                    }
                    entries.add(Parameter.ofParam(required, p.getType(), param.value(), defaultValue));
                }
                continue;
            }

            if (p.getType().isAssignableFrom(ServiceRequestContext.class) ||
                p.getType().isAssignableFrom(HttpParameters.class)) {
                entries.add(Parameter.ofPredefinedType(p.getType()));
                continue;
            }

            if (p.getType().isAssignableFrom(HttpRequest.class) ||
                p.getType().isAssignableFrom(AggregatedHttpMessage.class)) {
                if (hasRequestMessage) {
                    throw new IllegalArgumentException("Only one request message variable is allowed.");
                }
                hasRequestMessage = true;
                entries.add(Parameter.ofPredefinedType(p.getType()));
                continue;
            }

            throw new IllegalArgumentException("Unsupported object type: " + p.getType());
        }
        return entries.build();
    }

    /**
     * Returns array of parameters for method invocation.
     */
    private static Object[] parameterValues(ServiceRequestContext ctx, HttpRequest req,
                                            List<Parameter> parameters,
                                            @Nullable AggregatedHttpMessage message) {
        HttpParameters httpParameters = null;
        Object[] values = new Object[parameters.size()];
        for (int i = 0; i < parameters.size(); ++i) {
            Parameter entry = parameters.get(i);
            final String value;
            switch (entry.parameterType()) {
                case PATH_PARAM:
                    value = ctx.pathParam(entry.name());
                    assert value != null;
                    values[i] = convertParameter(value, entry.type());
                    break;
                case PARAM:
                    if (httpParameters == null) {
                        httpParameters = httpParametersOf(ctx, req, message);
                    }
                    value = httpParameterValue(httpParameters, entry);
                    values[i] = value != null ? convertParameter(value, entry.type()) : null;
                    break;
                case PREDEFINED_TYPE:
                    if (entry.type().isAssignableFrom(ServiceRequestContext.class)) {
                        values[i] = ctx;
                    } else if (entry.type().isAssignableFrom(HttpRequest.class)) {
                        values[i] = req;
                    } else if (entry.type().isAssignableFrom(AggregatedHttpMessage.class)) {
                        values[i] = message;
                    } else if (entry.type().isAssignableFrom(HttpParameters.class)) {
                        if (httpParameters == null) {
                            httpParameters = httpParametersOf(ctx, req, message);
                        }
                        values[i] = httpParameters;
                    }
                    break;
            }
        }
        return values;
    }

    /**
     * Returns a map of parameters decoded from a request.
     *
     * <p>Usually one of a query string of a URI or URL-encoded form data is specified in the request.
     * If both of them exist though, they would be decoded and merged into a parameter map.</p>
     *
     * <p>Names and values of the parameters would be decoded as UTF-8 character set.</p>
     * @see QueryStringDecoder#QueryStringDecoder(String, boolean)
     * @see HttpConstants#DEFAULT_CHARSET
     */
    private static HttpParameters httpParametersOf(ServiceRequestContext ctx, HttpRequest req,
                                                   @Nullable AggregatedHttpMessage message) {
        try {
            Map<String, List<String>> parameters = null;
            final String query = ctx.query();
            if (query != null) {
                parameters = new QueryStringDecoder(query, false).parameters();
            }
            if (AggregationStrategy.aggregationAvailable(req)) {
                assert message != null;
                final String body = message.content().toStringAscii();
                if (!body.isEmpty()) {
                    final Map<String, List<String>> p =
                            new QueryStringDecoder(body, false).parameters();
                    if (parameters == null) {
                        parameters = p;
                    } else if (p != null) {
                        parameters.putAll(p);
                    }
                }
            }
            if (parameters == null || parameters.isEmpty()) {
                return EMPTY_PARAMETERS;
            }
            return HttpParameters.copyOf(parameters);

        } catch (Exception e) {
            // If we failed to decode the query string, we ignore the exception raised here.
            // A missing parameter might be checked when invoking the annotated method.
            logger.debug("Failed to decode query string: {}", e);
            return EMPTY_PARAMETERS;
        }
    }

    /**
     * Returns the value of the specified parameter name.
     */
    @Nullable
    private static String httpParameterValue(HttpParameters httpParameters, Parameter entry) {
        String value = httpParameters.get(entry.name());
        if (value != null) {
            // The first decoded value.
            return value;
        }
        if (!entry.isRequired()) {
            // May return null if no default value is specified.
            return entry.defaultValue();
        }

        throw new IllegalArgumentException("Required parameter '" + entry.name() + "' is missing.");
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
                    "Can't convert '" + str + "' to type '" + clazz.getSimpleName() + "'.", e);
        }

        throw new IllegalArgumentException(
                "Type '" + clazz.getSimpleName() + "' can't be converted.");
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
        throw new IllegalArgumentException("Converter not available for: " + type.getSimpleName());
    }

    /**
     * Parameter entry, which will be used to invoke the {@link AnnotatedHttpService}.
     */
    private static final class Parameter {

        static Parameter ofPathParam(Class<?> type, String name) {
            return new Parameter(ParameterType.PATH_PARAM, true, type, name, null);
        }

        static Parameter ofParam(boolean required, Class<?> type, String name, @Nullable String defaultValue) {
            return new Parameter(ParameterType.PARAM, required, type, name, defaultValue);
        }

        static Parameter ofPredefinedType(Class<?> type) {
            return new Parameter(ParameterType.PREDEFINED_TYPE, true, type, null, null);
        }

        private final ParameterType parameterType;
        private final boolean required;
        private final Class<?> type;
        private final String name;
        private final String defaultValue;

        Parameter(ParameterType parameterType,
                  boolean required, Class<?> type,
                  @Nullable String name, @Nullable String defaultValue) {
            this.parameterType = parameterType;
            this.required = required;
            this.type = requireNonNull(type, "type");
            this.name = name;
            this.defaultValue = defaultValue;
        }

        ParameterType parameterType() {
            return parameterType;
        }

        boolean isRequired() {
            return required;
        }

        Class<?> type() {
            return type;
        }

        @Nullable
        String name() {
            return name;
        }

        @Nullable
        String defaultValue() {
            return defaultValue;
        }

        boolean isPathParam() {
            return parameterType() == ParameterType.PATH_PARAM;
        }
    }

    private enum ParameterType {
        PATH_PARAM, PARAM, PREDEFINED_TYPE
    }

    private enum AggregationStrategy {
        NONE, ALWAYS, FOR_FORM_DATA;

        /**
         * Whether the request should be aggregated.
         */
        static boolean aggregationRequired(AggregationStrategy strategy, HttpRequest req) {
            requireNonNull(strategy, "strategy");
            switch (strategy) {
                case ALWAYS:
                    return true;
                case FOR_FORM_DATA:
                    return aggregationAvailable(req);
            }
            return false;
        }

        /**
         * Whether the request is available to be aggregated.
         */
        static boolean aggregationAvailable(HttpRequest req) {
            final String contentType = req.headers().get(HttpHeaderNames.CONTENT_TYPE);
            // We aggregate request stream messages for the media type of form data currently.
            return contentType != null &&
                   MediaType.FORM_DATA.toString().equals(HttpUtil.getMimeType(contentType));
        }

        /**
         * Returns {@link AggregationStrategy} which specifies how to aggregate the request
         * for injecting its parameters.
         */
        static AggregationStrategy resolve(List<Parameter> parameters) {
            AggregationStrategy strategy = NONE;
            for (Parameter p : parameters) {
                if (p.type().isAssignableFrom(AggregatedHttpMessage.class)) {
                    return ALWAYS;
                }
                if (p.parameterType() == ParameterType.PARAM ||
                    p.type().isAssignableFrom(HttpParameters.class)) {
                    strategy = FOR_FORM_DATA;
                }
            }
            return strategy;
        }
    }
}
