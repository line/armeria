/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.aggregationAvailable;
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.findHeaderName;
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.findParamName;
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.httpParametersOf;
import static com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.validateAndNormalizeSupportedType;
import static com.linecorp.armeria.internal.DefaultValues.getSpecifiedValue;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpParameters;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil;
import com.linecorp.armeria.internal.AnnotatedHttpServiceParamUtil.EnumConverter;
import com.linecorp.armeria.internal.FallthroughException;
import com.linecorp.armeria.server.annotation.BeanRequestConverterFunction;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.RequestObject;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

import io.netty.util.AsciiString;

/**
 * Invokes an individual method of an annotated service. An annotated service method whose return type is not
 * {@link CompletionStage} or {@link HttpResponse} will be run in the blocking task executor.
 */
final class AnnotatedHttpServiceMethod {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedHttpServiceMethod.class);

    private final Object object;
    private final Method method;
    private final List<Parameter> parameters;
    private final boolean isAsynchronous;
    private final AggregationStrategy aggregationStrategy;
    private final List<ExceptionHandlerFunction> exceptionHandlers;
    private final List<RequestConverterFunction> requestConverters;
    private final List<ResponseConverterFunction> responseConverters;

    AnnotatedHttpServiceMethod(Object object, Method method, PathMapping pathMapping,
                               List<ExceptionHandlerFunction> exceptionHandlers,
                               List<RequestConverterFunction> requestConverters,
                               List<ResponseConverterFunction> responseConverters) {
        this.object = requireNonNull(object, "object");
        this.method = requireNonNull(method, "method");
        requireNonNull(pathMapping, "pathMapping");
        this.exceptionHandlers = ImmutableList.copyOf(
                requireNonNull(exceptionHandlers, "exceptionHandlers"));
        this.requestConverters = ImmutableList.copyOf(
                requireNonNull(requestConverters, "requestConverters"));
        this.responseConverters = ImmutableList.copyOf(
                requireNonNull(responseConverters, "responseConverters"));

        parameters = parameters(method, pathMapping.paramNames(), !requestConverters.isEmpty());
        final Class<?> returnType = method.getReturnType();
        isAsynchronous = HttpResponse.class.isAssignableFrom(returnType) ||
                         CompletionStage.class.isAssignableFrom(returnType);
        aggregationStrategy = AggregationStrategy.resolve(parameters);

        this.method.setAccessible(true);
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

    /**
     * Returns the result of the execution.
     */
    public CompletionStage<HttpResponse> serve(ServiceRequestContext ctx, HttpRequest req) {
        final Object ret = executeServiceMethod(ctx, req);
        if (ret instanceof CompletionStage) {
            return ((CompletionStage<?>) ret).handle((result, cause) -> {
                if (cause == null) {
                    return convertResponse(ctx, result);
                }
                final HttpResponse response = convertException(ctx, req, cause);
                if (response != null) {
                    return response;
                } else {
                    return Exceptions.throwUnsafely(cause);
                }
            });
        }
        if (ret instanceof HttpResponse) {
            return CompletableFuture.completedFuture(
                    new ExceptionFilteredHttpResponse(ctx, req, (HttpResponse) ret));
        }
        return CompletableFuture.completedFuture(convertResponse(ctx, ret));
    }

    /**
     * Executes the service method in different ways regarding its return type and whether the request is
     * required to be aggregated.
     */
    private Object executeServiceMethod(ServiceRequestContext ctx, HttpRequest req) {
        if (AggregationStrategy.aggregationRequired(aggregationStrategy, req)) {
            final CompletableFuture<AggregatedHttpMessage> aggregationFuture = req.aggregate();
            if (CompletionStage.class.isAssignableFrom(method.getReturnType())) {
                return aggregationFuture.thenCompose(msg -> toCompletionStage(invoke(ctx, req, msg)));
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

    /**
     * Wraps the specified {@code obj} with {@link CompletableFuture} if it is not an instance of
     * {@link CompletionStage}.
     */
    private static CompletionStage<?> toCompletionStage(Object obj) {
        if (obj instanceof CompletionStage) {
            return (CompletionStage<?>) obj;
        }
        return CompletableFuture.completedFuture(obj);
    }

    /**
     * Invokes the service method with arguments.
     */
    private Object invoke(ServiceRequestContext ctx, HttpRequest req, @Nullable AggregatedHttpMessage message) {
        try (SafeCloseable ignored = RequestContext.push(ctx, false)) {
            return method.invoke(object, parameterValues(ctx, req, parameters, message, requestConverters));
        } catch (Throwable cause) {
            final HttpResponse response = convertException(ctx, req, cause);
            if (response != null) {
                return response;
            }
            return Exceptions.throwUnsafely(cause);
        }
    }

    /**
     * Converts the specified {@code result} to {@link HttpResponse}.
     */
    private HttpResponse convertResponse(ServiceRequestContext ctx, @Nullable Object result) {
        if (result instanceof HttpResponse) {
            return (HttpResponse) result;
        }
        if (result instanceof AggregatedHttpMessage) {
            return HttpResponse.of((AggregatedHttpMessage) result);
        }

        try (SafeCloseable ignored = RequestContext.push(ctx, false)) {
            for (final ResponseConverterFunction func : responseConverters) {
                try {
                    return func.convertResponse(ctx, result);
                } catch (FallthroughException ignore) {
                    // Do nothing.
                } catch (Exception e) {
                    throw new IllegalStateException(
                            "Response converter " + func.getClass().getName() +
                            " cannot convert a result to HttpResponse: " + result, e);
                }
            }
        }
        // There is no response converter which is able to convert 'null' result to a response.
        // In this case, we deal the result as '204 No Content' instead of '500 Internal Server Error'.
        if (result == null) {
            return HttpResponse.of(HttpStatus.NO_CONTENT);
        }
        throw new IllegalStateException(
                "No response converter exists for a result: " + result.getClass().getSimpleName());
    }

    /**
     * Returns a {@link HttpResponse} which is created by {@link ExceptionHandlerFunction}.
     */
    @Nullable // Poorly-written user function can still return null.
    private HttpResponse convertException(ServiceRequestContext ctx, HttpRequest req,
                                          Throwable cause) {
        final Throwable peeledCause = Exceptions.peel(cause);
        for (final ExceptionHandlerFunction func : exceptionHandlers) {
            try {
                return func.handleException(ctx, req, peeledCause);
            } catch (FallthroughException ignore) {
                // Do nothing.
            } catch (Exception e) {
                logger.warn("Unexpected exception from an exception handler {}:",
                            func.getClass().getName(), e);
            }
        }
        return ExceptionHandlerFunction.DEFAULT.handleException(ctx, req, peeledCause);
    }

    /**
     * Returns the array of {@link Parameter}, which holds the type and {@link Param} value.
     */
    private static List<Parameter> parameters(Method method, Set<String> pathParams,
                                              boolean hasRequestConverters) {
        requireNonNull(pathParams, "pathParams");
        boolean hasRequestMessage = false;
        final ImmutableList.Builder<Parameter> entries = ImmutableList.builder();
        for (java.lang.reflect.Parameter parameterInfo : method.getParameters()) {
            final String param = findParamName(parameterInfo);
            if (param != null) {
                if (pathParams.contains(param)) {
                    if (parameterInfo.getAnnotation(Default.class) != null ||
                        parameterInfo.getType() == Optional.class) {
                        throw new IllegalArgumentException(
                                "Path variable '" + param + "' should not be an optional.");
                    }
                    entries.add(Parameter.ofPathParam(
                            validateAndNormalizeSupportedType(parameterInfo.getType()), param));
                } else {
                    entries.add(createHttpComponentParameter(
                            parameterInfo, ParameterType.PARAM, param));
                }
                continue;
            }

            final String header = findHeaderName(parameterInfo);
            if (header != null) {
                entries.add(createHttpComponentParameter(parameterInfo, ParameterType.HEADER, header));
                continue;
            }

            final RequestObject requestObject = parameterInfo.getAnnotation(RequestObject.class);
            if (requestObject != null) {
                // There is a converter which is specified by a user.
                if (requestObject.value() != RequestConverterFunction.class) {
                    final RequestConverterFunction requestConverterFunction =
                            AnnotatedHttpServices.getInstance(requestObject, RequestConverterFunction.class);
                    entries.add(Parameter.ofRequestObject(parameterInfo.getType(), requestConverterFunction));
                } else {
                    checkArgument(hasRequestConverters,
                                  "No request converter for method: " + method.getName());
                    entries.add(Parameter.ofRequestObject(parameterInfo.getType(), null));
                }
                continue;
            }

            if (parameterInfo.getType() == RequestContext.class ||
                parameterInfo.getType() == ServiceRequestContext.class ||
                parameterInfo.getType() == HttpParameters.class) {
                entries.add(Parameter.ofPredefinedType(parameterInfo.getType()));
                continue;
            }

            if (parameterInfo.getType() == Request.class ||
                parameterInfo.getType() == HttpRequest.class ||
                parameterInfo.getType() == AggregatedHttpMessage.class) {
                if (hasRequestMessage) {
                    throw new IllegalArgumentException("Only one request message variable is allowed.");
                }
                hasRequestMessage = true;
                entries.add(Parameter.ofPredefinedType(parameterInfo.getType()));
                continue;
            }

            throw new IllegalArgumentException("Unsupported object type: " + parameterInfo.getType());
        }
        return entries.build();
    }

    /**
     * Creates a {@link Parameter} instance which describes a parameter of the annotated method. If the
     * parameter type is {@link Optional}, its actual argument's type is used.
     */
    private static Parameter createHttpComponentParameter(java.lang.reflect.Parameter parameterInfo,
                                                          ParameterType paramType, String paramValue) {
        assert paramType == ParameterType.PARAM || paramType == ParameterType.HEADER
                : String.valueOf(paramType);

        final Default aDefault = parameterInfo.getAnnotation(Default.class);
        // Set the default value to null if it was not specified.
        final String defaultValue = aDefault != null ? getSpecifiedValue(aDefault.value()).get() : null;

        final Class<?> type;
        final Class<?> wrapperType;

        final Type parameterizedType = parameterInfo.getParameterizedType();
        if (parameterizedType instanceof ParameterizedType) {
            try {
                type = (Class<?>) ((ParameterizedType) parameterizedType).getActualTypeArguments()[0];
            } catch (Throwable cause) {
                throw new IllegalArgumentException("Invalid optional parameter: " + parameterInfo.getName(),
                                                   cause);
            }
            wrapperType = validateWrapperAndElementType(paramType, parameterInfo.getType(), type);
        } else {
            type = parameterInfo.getType();
            wrapperType = null;
        }

        final boolean isRequired = wrapperType != Optional.class && aDefault == null;
        return new Parameter(paramType, isRequired,
                             validateAndNormalizeSupportedType(type), wrapperType,
                             paramValue, defaultValue, null);
    }

    private static Class<?> validateWrapperAndElementType(ParameterType paramType,
                                                          Class<?> clazz, Class<?> elementClazz) {
        assert paramType == ParameterType.PARAM || paramType == ParameterType.HEADER
                : String.valueOf(paramType);

        if (clazz == Optional.class) {
            return Optional.class;
        }

        // A list of string is supported only for HTTP headers.
        if (paramType == ParameterType.HEADER && elementClazz == String.class) {
            if (clazz == Iterable.class || clazz == List.class || clazz == Collection.class) {
                return ArrayList.class;
            }
            if (clazz == Set.class) {
                return LinkedHashSet.class;
            }
            if (List.class.isAssignableFrom(clazz) || Set.class.isAssignableFrom(clazz)) {
                try {
                    // Only if there is a default constructor.
                    clazz.getConstructor();
                    return clazz;
                } catch (Throwable cause) {
                    throw new IllegalArgumentException("Unsupported type: " + clazz.getName() +
                                                       '<' + String.class.getName() + '>', cause);
                }
            }
        }
        throw new IllegalArgumentException("Unsupported type: " + clazz.getName() +
                                           '<' + elementClazz.getName() + '>');
    }

    /**
     * Returns array of parameters for method invocation.
     */
    private static Object[] parameterValues(ServiceRequestContext ctx, HttpRequest req,
                                            List<Parameter> parameters,
                                            @Nullable AggregatedHttpMessage message,
                                            List<RequestConverterFunction> requestConverters) throws Exception {
        HttpParameters httpParameters = null;
        final Object[] values = new Object[parameters.size()];
        for (int i = 0; i < parameters.size(); ++i) {
            final Parameter entry = parameters.get(i);
            final String value;
            switch (entry.parameterType()) {
                case PATH_PARAM:
                    value = ctx.pathParam(entry.name());
                    assert value != null;
                    values[i] = convertParameter(value, entry);
                    break;
                case PARAM:
                    if (httpParameters == null) {
                        httpParameters = httpParametersOf(ctx, req.headers(), message);
                    }
                    values[i] = httpParameterValue(httpParameters, entry);
                    break;
                case HEADER:
                    values[i] = httpHeaderValue(entry, req);
                    break;
                case PREDEFINED_TYPE:
                    if (entry.type() == RequestContext.class ||
                        entry.type() == ServiceRequestContext.class) {
                        values[i] = ctx;
                    } else if (entry.type() == Request.class ||
                               entry.type() == HttpRequest.class) {
                        values[i] = req;
                    } else if (entry.type() == AggregatedHttpMessage.class) {
                        values[i] = message;
                    } else if (entry.type() == HttpParameters.class) {
                        if (httpParameters == null) {
                            httpParameters = httpParametersOf(ctx, req.headers(), message);
                        }
                        values[i] = httpParameters;
                    }
                    break;
                case REQUEST_OBJECT:
                    final RequestConverterFunction converter = entry.requestConverterFunction();
                    if (converter != null) {
                        try {
                            values[i] = convertRequest(converter, ctx, message, entry);
                        } catch (FallthroughException ignore) {
                            // Do nothing.
                        }
                    }
                    if (values[i] == null) {
                        for (final RequestConverterFunction func : requestConverters) {
                            try {
                                values[i] = convertRequest(func, ctx, message, entry);
                                break;
                            } catch (FallthroughException ignore) {
                                // Do nothing.
                            }
                        }
                    }
                    checkArgument(values[i] != null,
                                  "No suitable request converter found for a @" +
                                  RequestObject.class.getSimpleName() + " '" + entry.name() + '\'');
                    break;
            }
        }
        return values;
    }

    /**
     * Converts the {@code request} to an object by {@link RequestConverterFunction}.
     */
    private static Object convertRequest(RequestConverterFunction converter,
                                         ServiceRequestContext ctx, AggregatedHttpMessage request,
                                         Parameter entry) throws Exception {
        final Object obj = converter.convertRequest(ctx, request, entry.type());
        checkArgument(obj != null,
                      "'null' is returned from " + converter.getClass().getName() +
                      " while converting a @" + RequestObject.class.getSimpleName() +
                      " '" + entry.name() + '\'');
        return obj;
    }

    /**
     * Returns the value of the specified parameter name.
     */
    @Nullable
    private static Object httpParameterValue(HttpParameters httpParameters, Parameter entry) {
        // The first decoded value.
        final String value = httpParameters.get(entry.name());
        return convertParameter(value != null ? value
                                              : entryDefaultValue(entry), entry);
    }

    @Nullable
    private static String entryDefaultValue(Parameter entry) {
        if (!entry.isRequired()) {
            // May return null if no default value is specified.
            return entry.defaultValue();
        }

        throw new IllegalArgumentException("Required parameter '" + entry.name() + "' is missing.");
    }

    @Nullable
    private static Object convertParameter(@Nullable String value, Parameter entry) {
        return AnnotatedHttpServiceParamUtil.convertParameter(value,
                                                              entry.type(),
                                                              entry.enumConverter(),
                                                              entry.isOptionalWrapped());
    }

    @Nullable
    private static Object httpHeaderValue(Parameter entry, HttpRequest req) {
        final String name = entry.name();
        assert name != null;

        final Class<?> wrapperType = entry.wrapperType();
        if (wrapperType != null &&
            (List.class.isAssignableFrom(wrapperType) || Set.class.isAssignableFrom(wrapperType))) {
            assert entry.type() == String.class : entry.type().getName();
            try {
                @SuppressWarnings("unchecked")
                final Collection<String> value = (Collection<String>) wrapperType.newInstance();

                // We do not call convertParameter() here because the element type is String.
                final List<String> headerValues = req.headers().getAll(AsciiString.of(name));
                if (headerValues != null && !headerValues.isEmpty()) {
                    value.addAll(headerValues);
                } else {
                    final String defaultValue = entryDefaultValue(entry);
                    if (defaultValue != null) {
                        value.add(defaultValue);
                    }
                }
                return value;
            } catch (Throwable cause) {
                throw new IllegalArgumentException("Cannot get the value of an HTTP header: " + name, cause);
            }
        }

        final String value = req.headers().get(AsciiString.of(name));
        return convertParameter(value != null ? value
                                              : entryDefaultValue(entry), entry);
    }

    /**
     * Intercepts a {@link Throwable} raised from {@link HttpResponse} and then rewrites it as an
     * {@link HttpResponseException} by {@link ExceptionHandlerFunction}.
     */
    private class ExceptionFilteredHttpResponse extends FilteredHttpResponse {

        private final ServiceRequestContext ctx;
        private final HttpRequest req;

        ExceptionFilteredHttpResponse(ServiceRequestContext ctx, HttpRequest req,
                                      HttpResponse delegate) {
            super(delegate);
            this.ctx = ctx;
            this.req = req;
        }

        @Override
        protected HttpObject filter(HttpObject obj) {
            return obj;
        }

        @Override
        protected Throwable beforeError(Subscriber<? super HttpObject> subscriber,
                                        Throwable cause) {
            final HttpResponse response = convertException(ctx, req, cause);
            return response != null ? HttpResponseException.of(response)
                                    : cause;
        }
    }

    /**
     * Parameter entry, which will be used to invoke the {@link AnnotatedHttpService}.
     */
    private static final class Parameter {

        static Parameter ofPathParam(Class<?> type, String name) {
            return new Parameter(ParameterType.PATH_PARAM, true, type, null,
                                 name, null, null);
        }

        static Parameter ofPredefinedType(Class<?> type) {
            return new Parameter(ParameterType.PREDEFINED_TYPE, true, type, null,
                                 null, null, null);
        }

        static Parameter ofRequestObject(Class<?> type,
                                         @Nullable RequestConverterFunction requestConverterFunction) {
            BeanRequestConverterFunction.register(type);

            return new Parameter(ParameterType.REQUEST_OBJECT, true, type, null,
                                 null, null, requestConverterFunction);
        }

        private final ParameterType parameterType;
        private final boolean isRequired;
        private final boolean isOptionalWrapped;
        private final Class<?> type;
        @Nullable
        private final Class<?> wrapperType;
        @Nullable
        private final String name;
        @Nullable
        private final String defaultValue;
        @Nullable
        private final RequestConverterFunction requestConverterFunction;

        @Nullable
        private final EnumConverter<?> enumConverter;

        Parameter(ParameterType parameterType,
                  boolean isRequired, Class<?> type, @Nullable Class<?> wrapperType,
                  @Nullable String name, @Nullable String defaultValue,
                  @Nullable RequestConverterFunction requestConverterFunction) {
            this.parameterType = parameterType;
            this.isRequired = isRequired;
            this.type = requireNonNull(type, "type");
            this.wrapperType = wrapperType;
            this.name = name;
            this.defaultValue = defaultValue;
            this.requestConverterFunction = requestConverterFunction;

            isOptionalWrapped = wrapperType == Optional.class;
            if (type.isEnum()) {
                enumConverter = new EnumConverter<>(type.asSubclass(Enum.class));
            } else {
                enumConverter = null;
            }
        }

        ParameterType parameterType() {
            return parameterType;
        }

        boolean isRequired() {
            return isRequired;
        }

        public boolean isOptionalWrapped() {
            return isOptionalWrapped;
        }

        Class<?> type() {
            return type;
        }

        @Nullable
        Class<?> wrapperType() {
            return wrapperType;
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

        @Nullable
        RequestConverterFunction requestConverterFunction() {
            return requestConverterFunction;
        }

        @Nullable
        EnumConverter<?> enumConverter() {
            return enumConverter;
        }
    }

    private enum ParameterType {
        PATH_PARAM, PARAM, HEADER, PREDEFINED_TYPE, REQUEST_OBJECT
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
                    return aggregationAvailable(req.headers());
            }
            return false;
        }

        /**
         * Returns {@link AggregationStrategy} which specifies how to aggregate the request
         * for injecting its parameters.
         */
        static AggregationStrategy resolve(List<Parameter> parameters) {
            AggregationStrategy strategy = NONE;
            for (Parameter p : parameters) {
                if (p.parameterType() == ParameterType.REQUEST_OBJECT ||
                    p.type() == AggregatedHttpMessage.class) {
                    return ALWAYS;
                }
                if (p.parameterType() == ParameterType.PARAM ||
                    p.type() == HttpParameters.class) {
                    strategy = FOR_FORM_DATA;
                }
            }
            return strategy;
        }
    }
}
