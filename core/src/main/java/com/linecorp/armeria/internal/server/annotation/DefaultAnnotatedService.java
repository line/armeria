/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.internal.server.annotation.ResponseConverterFunctionUtil.newResponseConverter;
import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.server.FileAggregatedMultipart;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.AggregatedResult;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.AggregationStrategy;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.AggregationType;
import com.linecorp.armeria.internal.server.annotation.AnnotatedValueResolver.ResolverContext;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServiceOption;
import com.linecorp.armeria.server.ServiceOptions;
import com.linecorp.armeria.server.ServiceOptionsBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.annotation.AnnotatedService;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.ExceptionVerbosity;
import com.linecorp.armeria.server.annotation.FallthroughException;
import com.linecorp.armeria.server.annotation.HttpResult;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ServiceName;

/**
 * An {@link HttpService} which is defined by a {@link Path} or HTTP method annotations.
 * This class is not supposed to be instantiated by a user. Please check out the documentation
 * <a href="https://armeria.dev/docs/server-annotated-service">Annotated HTTP Service</a> to use this.
 */
final class DefaultAnnotatedService implements AnnotatedService {
    private static final Logger logger = LoggerFactory.getLogger(DefaultAnnotatedService.class);

    private static final MethodHandles.Lookup lookup = MethodHandles.lookup();

    private static final CompletableFuture<AggregatedResult>
            NO_AGGREGATION_FUTURE = UnmodifiableFuture.completedFuture(AggregatedResult.EMPTY);

    private final Object object;
    private final Class<?> serviceClass;
    private final Method method;
    private final int overloadId;
    private final MethodHandle methodHandle;
    @Nullable
    private final MethodHandle callKotlinSuspendingMethod;
    private final boolean isKotlinSuspendingMethod;
    private final List<AnnotatedValueResolver> resolvers;

    private final AggregationStrategy aggregationStrategy;
    @Nullable
    private final ExceptionHandlerFunction exceptionHandler;
    private final ResponseConverterFunction responseConverter;
    private final Type actualReturnType;

    private final Route route;
    private final HttpStatus defaultStatus;
    private final HttpHeaders defaultHttpHeaders;
    private final HttpHeaders defaultHttpTrailers;

    private final ResponseType responseType;
    private final boolean useBlockingTaskExecutor;
    @Nullable
    private final String name;

    private final ServiceOptions options;

    DefaultAnnotatedService(Object object, Method method,
                            int overloadId, List<AnnotatedValueResolver> resolvers,
                            List<ExceptionHandlerFunction> exceptionHandlers,
                            List<ResponseConverterFunction> responseConverters,
                            Route route,
                            HttpStatus defaultStatus,
                            HttpHeaders defaultHttpHeaders,
                            HttpHeaders defaultHttpTrailers,
                            boolean useBlockingTaskExecutor) {
        this.object = requireNonNull(object, "object");
        this.method = requireNonNull(method, "method");
        checkArgument(overloadId >= 0, "overloadId: %s (expected: >= 0)", overloadId);
        this.overloadId = overloadId;
        serviceClass = ClassUtil.getUserClass(object.getClass());

        checkArgument(!method.isVarArgs(), "%s#%s declared to take a variable number of arguments",
                      method.getDeclaringClass().getSimpleName(), method.getName());
        isKotlinSuspendingMethod = KotlinUtil.isSuspendingFunction(method);
        this.resolvers = requireNonNull(resolvers, "resolvers");

        requireNonNull(exceptionHandlers, "exceptionHandlers");
        if (exceptionHandlers.isEmpty()) {
            exceptionHandler = null;
        } else {
            exceptionHandler = new CompositeExceptionHandlerFunction(object.getClass().getSimpleName(),
                                                                     method.getName(), exceptionHandlers);
        }

        actualReturnType = getActualReturnType(method);
        responseConverter = newResponseConverter(
                actualReturnType, requireNonNull(responseConverters, "responseConverters"));
        aggregationStrategy = AggregationStrategy.from(resolvers);
        this.route = requireNonNull(route, "route");

        this.defaultStatus = requireNonNull(defaultStatus, "defaultStatus");
        this.defaultHttpHeaders = requireNonNull(defaultHttpHeaders, "defaultHttpHeaders");
        this.defaultHttpTrailers = requireNonNull(defaultHttpTrailers, "defaultHttpTrailers");
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        final Class<?> returnType = method.getReturnType();
        if (HttpResponse.class.isAssignableFrom(returnType)) {
            responseType = ResponseType.HTTP_RESPONSE;
        } else if (CompletionStage.class.isAssignableFrom(returnType)) {
            responseType = ResponseType.COMPLETION_STAGE;
        } else if (isKotlinSuspendingMethod) {
            responseType = ResponseType.KOTLIN_COROUTINES;
        } else if (ScalaUtil.isScalaFuture(returnType)) {
            responseType = ResponseType.SCALA_FUTURE;
        } else {
            responseType = ResponseType.OTHER_OBJECTS;
        }
        callKotlinSuspendingMethod = KotlinUtil.getCallKotlinSuspendingMethod();

        ServiceName serviceName = AnnotationUtil.findFirst(method, ServiceName.class);
        if (serviceName == null) {
            serviceName = AnnotationUtil.findFirst(object.getClass(), ServiceName.class);
        }
        if (serviceName != null) {
            name = serviceName.value();
        } else {
            name = null;
        }

        this.method.setAccessible(true);
        // following must be called only after method.setAccessible(true)
        methodHandle = asMethodHandle(method, object);

        ServiceOption serviceOption = AnnotationUtil.findFirst(method, ServiceOption.class);
        if (serviceOption == null) {
            serviceOption = AnnotationUtil.findFirst(object.getClass(), ServiceOption.class);
        }
        if (serviceOption != null) {
            options = buildServiceOptions(serviceOption);
        } else {
            options = ServiceOptions.of();
        }
    }

    private static Type getActualReturnType(Method method) {
        final Class<?> returnType;
        final Type genericReturnType;

        if (KotlinUtil.isKFunction(method)) {
            returnType = KotlinUtil.kFunctionReturnType(method);
            if (KotlinUtil.isReturnTypeNothing(method)) {
                genericReturnType = KotlinUtil.kFunctionReturnType(method);
            } else {
                genericReturnType = KotlinUtil.kFunctionGenericReturnType(method);
            }
        } else {
            returnType = method.getReturnType();
            genericReturnType = method.getGenericReturnType();
        }

        if (HttpResult.class.isAssignableFrom(returnType) ||
            ResponseEntity.class.isAssignableFrom(returnType)) {
            final ParameterizedType type = (ParameterizedType) genericReturnType;
            warnIfHttpResponseArgumentExists(type, type, returnType);
            return type.getActualTypeArguments()[0];
        } else {
            return genericReturnType;
        }
    }

    private static void warnIfHttpResponseArgumentExists(Type returnType,
                                                         ParameterizedType type,
                                                         Class<?> originalReturnType) {
        for (final Type arg : type.getActualTypeArguments()) {
            if (arg instanceof ParameterizedType) {
                warnIfHttpResponseArgumentExists(returnType, (ParameterizedType) arg, originalReturnType);
            } else if (arg instanceof Class) {
                final Class<?> clazz = (Class<?>) arg;
                if (HttpResponse.class.isAssignableFrom(clazz) ||
                    AggregatedHttpResponse.class.isAssignableFrom(clazz)) {
                    logger.warn("{} in the return type '{}' may take precedence over {}.",
                                clazz.getSimpleName(), returnType, originalReturnType.getSimpleName());
                }
            }
        }
    }

    private static ServiceOptions buildServiceOptions(ServiceOption serviceOption) {
        final ServiceOptionsBuilder builder = ServiceOptions.builder();
        if (serviceOption.requestTimeoutMillis() >= 0) {
            builder.requestTimeoutMillis(serviceOption.requestTimeoutMillis());
        }
        if (serviceOption.maxRequestLength() >= 0) {
            builder.maxRequestLength(serviceOption.maxRequestLength());
        }
        if (serviceOption.requestAutoAbortDelayMillis() >= 0) {
            builder.requestAutoAbortDelayMillis(serviceOption.requestAutoAbortDelayMillis());
        }
        return builder.build();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Object serviceObject() {
        return object;
    }

    @Override
    public Class<?> serviceClass() {
        return serviceClass;
    }

    @Override
    public Method method() {
        return method;
    }

    int overloadId() {
        return overloadId;
    }

    List<AnnotatedValueResolver> annotatedValueResolvers() {
        return resolvers;
    }

    @Override
    public Route route() {
        return route;
    }

    @Override
    public HttpStatus defaultStatus() {
        return defaultStatus;
    }

    HttpService withExceptionHandler(HttpService service) {
        if (exceptionHandler == null) {
            return service;
        }
        return new ExceptionHandlingHttpService(service, exceptionHandler);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (!defaultHttpHeaders.isEmpty()) {
            ctx.mutateAdditionalResponseHeaders(mutator -> mutator.add(defaultHttpHeaders));
        }
        if (!defaultHttpTrailers.isEmpty()) {
            ctx.mutateAdditionalResponseTrailers(mutator -> mutator.add(defaultHttpTrailers));
        }

        final HttpResponse response = serve0(ctx, req);

        if (exceptionHandler == null) {
            // If an error occurs, the default ExceptionHandler will handle the error.
            if (Flags.annotatedServiceExceptionVerbosity() == ExceptionVerbosity.ALL &&
                logger.isWarnEnabled()) {
                return response.peekError(cause -> {
                    logger.warn("{} Exception raised by method '{}' in '{}':",
                                ctx, methodName(), object.getClass().getSimpleName(), Exceptions.peel(cause));
                });
            }
        }

        return response;
    }

    private HttpResponse serve0(ServiceRequestContext ctx, HttpRequest req) {
        final AggregationType aggregationType =
                AnnotatedValueResolver.aggregationType(aggregationStrategy, req.headers());

        if (aggregationType == AggregationType.NONE && !useBlockingTaskExecutor) {
            // Fast-path: No aggregation required and blocking task executor is not used.
            switch (responseType) {
                case HTTP_RESPONSE:
                    return (HttpResponse) invoke(ctx, req, AggregatedResult.EMPTY);
                case OTHER_OBJECTS:
                    return convertResponse(ctx, invoke(ctx, req, AggregatedResult.EMPTY));
            }
        }

        return HttpResponse.of(serve1(ctx, req, aggregationType));
    }

    /**
     * Executes the service method in different ways regarding its return type and whether the request is
     * required to be aggregated. If the return type of the method is not a {@link CompletionStage} or
     * {@link HttpResponse}, it will be executed in the blocking task executor.
     */
    private CompletionStage<HttpResponse> serve1(ServiceRequestContext ctx, HttpRequest req,
                                                 AggregationType aggregationType) {
        final CompletableFuture<AggregatedResult> f;
        switch (aggregationType) {
            case MULTIPART:
                f = FileAggregatedMultipart.aggregateMultipart(ctx, req).thenApply(AggregatedResult::new);
                break;
            case ALL:
                f = req.aggregate().thenApply(AggregatedResult::new);
                break;
            case NONE:
                f = NO_AGGREGATION_FUTURE;
                break;
            default:
                // Should never reach here.
                throw new Error();
        }

        switch (responseType) {
            case HTTP_RESPONSE:
                if (useBlockingTaskExecutor) {
                    return f.thenApplyAsync(aReq -> (HttpResponse) invoke(ctx, req, aReq),
                                            ctx.blockingTaskExecutor());
                } else {
                    return f.thenApply(aReq -> (HttpResponse) invoke(ctx, req, aReq));
                }
            case COMPLETION_STAGE:
            case KOTLIN_COROUTINES:
            case SCALA_FUTURE:
                final CompletableFuture<?> composedFuture;
                if (useBlockingTaskExecutor) {
                    composedFuture = f.thenComposeAsync(
                            aReq -> toCompletionStage(invoke(ctx, req, aReq), ctx.blockingTaskExecutor()),
                            ctx.blockingTaskExecutor());
                } else {
                    composedFuture = f.thenCompose(
                            aReq -> toCompletionStage(invoke(ctx, req, aReq), ctx.eventLoop()));
                }
                return composedFuture.thenApply(result -> convertResponse(ctx, result));
            default:
                final Function<AggregatedResult, HttpResponse> defaultApplyFunction =
                        aReq -> convertResponse(ctx, invoke(ctx, req, aReq));
                if (useBlockingTaskExecutor) {
                    return f.thenApplyAsync(defaultApplyFunction, ctx.blockingTaskExecutor());
                } else {
                    return f.thenApply(defaultApplyFunction);
                }
        }
    }

    /**
     * Invokes the service method with arguments.
     */
    @Nullable
    private Object invoke(ServiceRequestContext ctx, HttpRequest req, AggregatedResult aggregatedResult) {
        try (SafeCloseable ignored = ctx.push()) {
            final ResolverContext resolverContext = new ResolverContext(ctx, req, aggregatedResult);
            final Object[] arguments = AnnotatedValueResolver.toArguments(resolvers, resolverContext);
            if (isKotlinSuspendingMethod) {
                assert callKotlinSuspendingMethod != null;
                final ScheduledExecutorService executor;
                // The request context will be injected by ArmeriaRequestCoroutineContext
                if (useBlockingTaskExecutor) {
                    executor = ctx.blockingTaskExecutor().withoutContext();
                } else {
                    executor = ctx.eventLoop().withoutContext();
                }
                return callKotlinSuspendingMethod.invoke(
                        method, object, arguments,
                        executor,
                        ctx);
            } else {
                return methodHandle.invoke(arguments);
            }
        } catch (Throwable cause) {
            return HttpResponse.ofFailure(cause);
        }
    }

    /**
     * Converts the specified {@code result} to an {@link HttpResponse}.
     */
    private HttpResponse convertResponse(ServiceRequestContext ctx, @Nullable Object result) {
        if (result instanceof HttpResponse) {
            return (HttpResponse) result;
        }
        if (result instanceof AggregatedHttpResponse) {
            return ((AggregatedHttpResponse) result).toHttpResponse();
        }

        final ResponseHeaders headers;
        final HttpHeaders trailers;

        if (result instanceof ResponseEntity) {
            final ResponseEntity<?> responseEntity = (ResponseEntity<?>) result;
            headers = ResponseEntityUtil.buildResponseHeaders(ctx, responseEntity);
            result = responseEntity.hasContent() ? responseEntity.content() : null;
            trailers = responseEntity.trailers();
        } else if (result instanceof HttpResult) {
            final HttpResult<?> httpResult = (HttpResult<?>) result;
            headers = HttpResultUtil.buildResponseHeaders(ctx, httpResult);
            result = httpResult.content();
            trailers = httpResult.trailers();
        } else {
            headers = buildResponseHeaders(ctx);
            trailers = HttpHeaders.of();
        }

        return convertResponseInternal(ctx, headers, result, trailers);
    }

    private HttpResponse convertResponseInternal(ServiceRequestContext ctx,
                                                 ResponseHeaders headers,
                                                 @Nullable Object result,
                                                 HttpHeaders trailers) {
        if (result instanceof CompletionStage) {
            final CompletionStage<?> future = (CompletionStage<?>) result;
            return HttpResponse.of(
                    future.thenApply(object -> convertResponseInternal(ctx, headers, object, trailers)));
        }

        try (SafeCloseable ignored = ctx.push()) {
            return responseConverter.convertResponse(ctx, headers, result, trailers);
        } catch (Exception cause) {
            return HttpResponse.ofFailure(cause);
        }
    }

    private ResponseHeaders buildResponseHeaders(ServiceRequestContext ctx) {
        final ResponseHeadersBuilder builder = ResponseHeaders.builder(defaultStatus);
        if (builder.status().isContentAlwaysEmpty()) {
            return builder.build();
        }

        final MediaType negotiatedResponseMediaType = ctx.negotiatedResponseMediaType();
        if (negotiatedResponseMediaType != null) {
            builder.contentType(negotiatedResponseMediaType);
        }
        return builder.build();
    }

    /**
     * Converts the specified {@code obj} with {@link CompletableFuture}.
     */
    private static CompletionStage<?> toCompletionStage(@Nullable Object obj, ExecutorService executor) {
        if (obj instanceof CompletionStage) {
            return (CompletionStage<?>) obj;
        }
        if (obj != null && ScalaUtil.isScalaFuture(obj.getClass())) {
            return ScalaUtil.FutureConverter.toCompletableFuture((scala.concurrent.Future<?>) obj, executor);
        }
        return UnmodifiableFuture.completedFuture(obj);
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        final boolean isRequestStreaming =
                AnnotatedValueResolver.aggregationType(aggregationStrategy,
                                                       routingContext.headers()) != AggregationType.ALL;
        Boolean isResponseStreaming =
                responseConverter.isResponseStreaming(
                        actualReturnType, routingContext.result().routingResult()
                                                        .negotiatedResponseMediaType());
        if (isResponseStreaming == null) {
            isResponseStreaming = true;
        }
        if (isRequestStreaming) {
            return isResponseStreaming ? ExchangeType.BIDI_STREAMING : ExchangeType.REQUEST_STREAMING;
        } else {
            return isResponseStreaming ? ExchangeType.RESPONSE_STREAMING : ExchangeType.UNARY;
        }
    }

    @Override
    public ServiceOptions options() {
        return options;
    }

    /**
     * An {@link ExceptionHandlerFunction} which wraps a list of {@link ExceptionHandlerFunction}s.
     */
    private static final class CompositeExceptionHandlerFunction implements ExceptionHandlerFunction {

        private final String className;
        private final String methodName;
        private final List<ExceptionHandlerFunction> functions;

        CompositeExceptionHandlerFunction(String className, String methodName,
                                          List<ExceptionHandlerFunction> functions) {
            this.className = className;
            this.methodName = methodName;
            this.functions = ImmutableList.copyOf(functions);
        }

        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            final Throwable peeledCause = Exceptions.peel(cause);
            if (Flags.annotatedServiceExceptionVerbosity() == ExceptionVerbosity.ALL &&
                logger.isWarnEnabled()) {
                logger.warn("{} Exception raised by method '{}' in '{}':",
                            ctx, methodName, className, peeledCause);
            }

            for (final ExceptionHandlerFunction func : functions) {
                try {
                    final HttpResponse response = func.handleException(ctx, req, peeledCause);
                    // Check the return value just in case, then pass this exception to the default handler
                    // if it is null.
                    if (response == null) {
                        break;
                    }
                    return response;
                } catch (FallthroughException ignore) {
                    // Do nothing.
                } catch (Exception e) {
                    logger.warn("{} Unexpected exception from an exception handler {}:",
                                ctx, func.getClass().getName(), e);
                }
            }

            return HttpResponse.ofFailure(peeledCause);
        }
    }

    private static final class ExceptionHandlingHttpService extends SimpleDecoratingHttpService {

        private final ExceptionHandlerFunction exceptionHandler;

        ExceptionHandlingHttpService(HttpService service, ExceptionHandlerFunction exceptionHandler) {
            super(service);
            this.exceptionHandler = exceptionHandler;
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
            try {
                final HttpResponse response = unwrap().serve(ctx, req);
                return response.recover(cause -> {
                    try (SafeCloseable ignored = ctx.push()) {
                        return exceptionHandler.handleException(ctx, req, cause);
                    }
                });
            } catch (Exception ex) {
                return exceptionHandler.handleException(ctx, req, ex);
            }
        }
    }

    /**
     * Response type classification of the annotated {@link Method}.
     */
    private enum ResponseType {
        HTTP_RESPONSE, COMPLETION_STAGE, KOTLIN_COROUTINES, SCALA_FUTURE, OTHER_OBJECTS
    }

    /**
     * Converts {@link Method} to {@link MethodHandle}, optionally accepting {@code object} instance of the
     * declaring class in case of non-static methods. Result {@link MethodHandle} must be assigned to
     * a {@code final} field in order to enable Java compiler optimizations.
     * @param method the {@link Method} to be converted to a {@link MethodHandle}
     * @param object an instance of declaring class for non-static methods, or {@link null} for static methods
     * @return a {@link MethodHandle} corresponding to the supplied {@link Method}
     */
    private static MethodHandle asMethodHandle(Method method, @Nullable Object object) {
        MethodHandle methodHandle;
        try {
            // an investigation showed no difference in performance between the MethodHandle
            // obtained via either MethodHandles.Lookup#unreflect or MethodHandles.Lookup#findVirtual
            methodHandle = lookup.unreflect(method);
        } catch (IllegalAccessException e) {
            // this is extremely unlikely considering that we've already executed method.setAccessible(true)
            throw new RuntimeException(e);
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            // bind non-static methods to an instance of the declaring class
            methodHandle = methodHandle.bindTo(requireNonNull(object, "object"));
        }
        final int parameterCount = method.getParameterCount();
        // allows MethodHandle accepting an Object[] argument and
        // spreading its elements as positional arguments
        return methodHandle.asSpreader(Object[].class, parameterCount);
    }
}
