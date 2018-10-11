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

import static com.linecorp.armeria.server.AnnotatedValueResolver.AggregationStrategy.aggregationRequired;
import static com.linecorp.armeria.server.AnnotatedValueResolver.toArguments;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.FallthroughException;
import com.linecorp.armeria.internal.PublisherToHttpResponseConverter;
import com.linecorp.armeria.server.AnnotatedValueResolver.AggregationStrategy;
import com.linecorp.armeria.server.AnnotatedValueResolver.ResolverContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.ExceptionVerbosity;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider;

/**
 * A {@link Service} which is defined by {@link Path} or HTTP method annotations.
 */
final class AnnotatedHttpService implements HttpService {
    private static final Logger logger = LoggerFactory.getLogger(AnnotatedHttpService.class);

    static final ServiceLoader<ResponseConverterFunctionProvider> responseConverterFunctionProviders =
            ServiceLoader.load(ResponseConverterFunctionProvider.class,
                               AnnotatedHttpService.class.getClassLoader());

    private final Object object;
    private final Method method;
    private final List<AnnotatedValueResolver> resolvers;

    private final AggregationStrategy aggregationStrategy;
    private final List<ExceptionHandlerFunction> exceptionHandlers;
    private final List<ResponseConverterFunction> responseConverters;
    @Nullable
    private final ResponseConverterFunction providedResponseConverter;

    private final ResponseType responseType;

    AnnotatedHttpService(Object object, Method method,
                         List<AnnotatedValueResolver> resolvers,
                         List<ExceptionHandlerFunction> exceptionHandlers,
                         List<ResponseConverterFunction> responseConverters) {
        this.object = requireNonNull(object, "object");
        this.method = requireNonNull(method, "method");
        this.resolvers = requireNonNull(resolvers, "resolvers");
        this.exceptionHandlers = ImmutableList.copyOf(
                requireNonNull(exceptionHandlers, "exceptionHandlers"));
        this.responseConverters = ImmutableList.copyOf(
                requireNonNull(responseConverters, "responseConverters"));

        aggregationStrategy = AggregationStrategy.from(resolvers);
        providedResponseConverter = fromProvider(method);

        final Class<?> returnType = method.getReturnType();
        if (providedResponseConverter != null) {
            responseType = ResponseType.HANDLED_BY_SPI;
        } else if (HttpResponse.class.isAssignableFrom(returnType)) {
            responseType = ResponseType.HTTP_RESPONSE;
        } else if (CompletionStage.class.isAssignableFrom(returnType)) {
            responseType = ResponseType.COMPLETION_STAGE;
        } else {
            responseType = ResponseType.OTHER_OBJECTS;
        }

        this.method.setAccessible(true);
    }

    @Nullable
    private ResponseConverterFunction fromProvider(Method method) {
        final Type returnType = method.getGenericReturnType();

        if (returnType instanceof ParameterizedType) {
            final ParameterizedType p = (ParameterizedType) returnType;
            if (Publisher.class.isAssignableFrom(toClass(p.getRawType())) &&
                Publisher.class.isAssignableFrom(toClass(p.getActualTypeArguments()[0]))) {
                throw new IllegalStateException(
                        "Invalid return type of method '" + method.getName() + "'. " +
                        "Cannot support '" + p.getActualTypeArguments()[0].getTypeName() +
                        "' as a generic type of " + Publisher.class.getSimpleName());
            }
        }

        for (ResponseConverterFunctionProvider provider : responseConverterFunctionProviders) {
            final ResponseConverterFunction func =
                    provider.createResponseConverterFunction(returnType,
                                                             this::convertResponse,
                                                             this::convertException);
            if (func != null) {
                return func;
            }
        }
        return null;
    }

    private static Class<?> toClass(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        return Void.class;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return HttpResponse.from(serve0(ctx, req));
    }

    /**
     * Executes the service method in different ways regarding its return type and whether the request is
     * required to be aggregated. If the return type of the method is not a {@link CompletionStage} or
     * {@link HttpResponse}, it will be executed in the blocking task executor.
     */
    public CompletionStage<HttpResponse> serve0(ServiceRequestContext ctx, HttpRequest req) {
        final CompletableFuture<AggregatedHttpMessage> f =
                aggregationRequired(aggregationStrategy, req) ? req.aggregate()
                                                              : CompletableFuture.completedFuture(null);
        switch (responseType) {
            case HANDLED_BY_SPI:
                return f.thenApply(msg -> {
                    try {
                        final Object obj = invoke(ctx, req, msg);
                        if (obj instanceof HttpResponse) {
                            return (HttpResponse) obj;
                        }
                        assert providedResponseConverter != null;
                        return new ExceptionFilteredHttpResponse(
                                ctx, req, providedResponseConverter.convertResponse(ctx, obj));
                    } catch (Throwable cause) {
                        return convertException(ctx, req, cause);
                    }
                });
            case HTTP_RESPONSE:
                return f.thenApply(
                        msg -> new ExceptionFilteredHttpResponse(ctx, req,
                                                                 (HttpResponse) invoke(ctx, req, msg)));
            case COMPLETION_STAGE:
                return f.thenCompose(msg -> toCompletionStage(invoke(ctx, req, msg)))
                        .handle((result, cause) -> cause == null ? convertResponse(ctx, req, result)
                                                                 : convertException(ctx, req, cause));
            default:
                return f.thenApplyAsync(msg -> convertResponse(ctx, req, invoke(ctx, req, msg)),
                                        ctx.blockingTaskExecutor());
        }
    }

    /**
     * Invokes the service method with arguments.
     */
    private Object invoke(ServiceRequestContext ctx, HttpRequest req, @Nullable AggregatedHttpMessage message) {
        try (SafeCloseable ignored = ctx.push(false)) {
            final ResolverContext resolverContext = new ResolverContext(ctx, req, message);
            final Object[] arguments = toArguments(resolvers, resolverContext);
            return method.invoke(object, arguments);
        } catch (Throwable cause) {
            return convertException(ctx, req, cause);
        }
    }

    /**
     * Converts the specified {@code result} to {@link HttpResponse}.
     */
    private HttpResponse convertResponse(ServiceRequestContext ctx, HttpRequest req,
                                         @Nullable Object result) {
        if (result instanceof HttpResponse) {
            return (HttpResponse) result;
        }
        if (result instanceof AggregatedHttpMessage) {
            return HttpResponse.of((AggregatedHttpMessage) result);
        }
        if (result instanceof Publisher) {
            final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
            final Publisher<?> publisher = (Publisher<?>) result;
            publisher.subscribe(new PublisherToHttpResponseConverter(ctx, req, future,
                                                                     this::convertResponse,
                                                                     this::convertException));
            return HttpResponse.from(future);
        }

        return convertResponse(ctx, result);
    }

    private HttpResponse convertResponse(ServiceRequestContext ctx, @Nullable Object result) {
        try (SafeCloseable ignored = ctx.push(false)) {
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
     * Returns an {@link HttpResponse} which is created by {@link ExceptionHandlerFunction}.
     */
    private HttpResponse convertException(RequestContext ctx, HttpRequest req, Throwable cause) {
        final Throwable peeledCause = Exceptions.peel(cause);

        if (Flags.annotatedServiceExceptionVerbosity() == ExceptionVerbosity.ALL &&
            logger.isWarnEnabled()) {
            logger.warn("{} Exception raised by method '{}' in '{}':",
                        ctx, method.getName(), object.getClass().getSimpleName(), peeledCause);
        }

        for (final ExceptionHandlerFunction func : exceptionHandlers) {
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

        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
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
        protected Throwable beforeError(Subscriber<? super HttpObject> subscriber, Throwable cause) {
            return HttpResponseException.of(convertException(ctx, req, cause));
        }
    }

    /**
     * Response type classification of the annotated {@link Method}.
     */
    private enum ResponseType {
        HANDLED_BY_SPI, HTTP_RESPONSE, COMPLETION_STAGE, OTHER_OBJECTS
    }
}
