/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.internal.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;

public final class ClientUtil {

    /**
     * An undefined {@link URI} to create {@link WebClient} without specifying {@link URI}.
     */
    public static final URI UNDEFINED_URI = URI.create("http://undefined");

    public static <I extends Request, O extends Response, U extends Client<I, O>>
    O initContextAndExecuteWithFallback(
            U delegate,
            ClientRequestContextExtension ctx,
            Function<CompletableFuture<O>, O> futureConverter,
            BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory,
            I req) {

        requireNonNull(delegate, "delegate");
        requireNonNull(ctx, "ctx");
        requireNonNull(futureConverter, "futureConverter");
        requireNonNull(errorResponseFactory, "errorResponseFactory");

        boolean initialized = false;
        boolean success = false;
        try {
            final CompletableFuture<Boolean> initFuture = ctx.init();
            initialized = initFuture.isDone();
            if (initialized) {
                // Initialization has been done immediately.
                try {
                    success = initFuture.get();
                } catch (Exception e) {
                    throw UnprocessedRequestException.of(Exceptions.peel(e));
                }

                return initContextAndExecuteWithFallback(delegate, ctx, errorResponseFactory, success, req);
            } else {
                return futureConverter.apply(initFuture.handle((success0, cause) -> {
                    try {
                        if (cause != null) {
                            throw UnprocessedRequestException.of(Exceptions.peel(cause));
                        }

                        return initContextAndExecuteWithFallback(
                                delegate, ctx, errorResponseFactory, success0, req);
                    } catch (Throwable t) {
                        fail(ctx, t);
                        return errorResponseFactory.apply(ctx, t);
                    } finally {
                        ctx.finishInitialization(success0);
                    }
                }));
            }
        } catch (Throwable cause) {
            fail(ctx, cause);
            return errorResponseFactory.apply(ctx, cause);
        } finally {
            if (initialized) {
                ctx.finishInitialization(success);
            }
        }
    }

    private static <I extends Request, O extends Response, U extends Client<I, O>>
    O initContextAndExecuteWithFallback(
            U delegate, ClientRequestContextExtension ctx,
            BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory, boolean succeeded, I req)
            throws Exception {

        if (succeeded) {
            return pushAndExecute(delegate, ctx, req);
        } else {
            final Throwable cause = ctx.log().partial().requestCause();
            assert cause != null;

            // Context initialization has failed, which means:
            // - ctx.log() has been completed with an exception.
            // - ctx.request() has been aborted (if not null).
            // - the decorator chain was not invoked at all.
            // See `init()` and `failEarly()` in `DefaultClientRequestContext`.

            // Call the decorator chain anyway so that the request is seen by the decorators.
            final O res = pushAndExecute(delegate, ctx, req);

            // We will use the fallback response which is created from the exception
            // raised in ctx.init(), so the response returned can be aborted.
            if (res instanceof StreamMessage) {
                ((StreamMessage<?>) res).abort(cause);
            }

            // No need to call `fail()` because failed by `DefaultRequestContext.init()` already.
            return errorResponseFactory.apply(ctx, cause);
        }
    }

    public static <I extends Request, O extends Response, U extends Client<I, O>>
    O executeWithFallback(U delegate, ClientRequestContext ctx,
                          BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory, I req) {

        requireNonNull(delegate, "delegate");
        requireNonNull(ctx, "ctx");
        requireNonNull(errorResponseFactory, "errorResponseFactory");

        try {
            return pushAndExecute(delegate, ctx, req);
        } catch (Throwable cause) {
            fail(ctx, cause);
            return errorResponseFactory.apply(ctx, cause);
        }
    }

    public static <I extends Request, O extends Response, U extends PreClient<I, O>>
    O executeWithFallback(U execution,
                          PreClientRequestContext ctx, I req,
                          BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory) {
        try {
            return execution.execute(ctx, req);
        } catch (Exception e) {
            fail(ctx, e);
            return errorResponseFactory.apply(ctx, e);
        }
    }

    private static <I extends Request, O extends Response, U extends Client<I, O>>
    O pushAndExecute(U delegate, ClientRequestContext ctx, I req) throws Exception {
        try (SafeCloseable ignored = ctx.push()) {
            return delegate.execute(ctx, req);
        }
    }

    private static void fail(ClientRequestContext ctx, Throwable cause) {
        final HttpRequest req = ctx.request();
        if (req != null) {
            req.abort(cause);
        }

        final RequestLogBuilder logBuilder = ctx.logBuilder();
        logBuilder.endRequest(cause);
        logBuilder.endResponse(cause);
    }

    /**
     * Creates a new derived {@link ClientRequestContext}, replacing the requests.
     * If {@link ClientRequestContext#endpointGroup()} exists, a new {@link Endpoint} will be selected.
     */
    public static ClientRequestContext newDerivedContext(ClientRequestContext ctx,
                                                         @Nullable HttpRequest req,
                                                         @Nullable RpcRequest rpcReq,
                                                         boolean initialAttempt) {
        final RequestId id = ctx.options().requestIdGenerator().get();
        final EndpointGroup endpointGroup = ctx.endpointGroup();
        final ClientRequestContext derived;
        if (endpointGroup != null && !initialAttempt) {
            derived = ctx.newDerivedContext(id, req, rpcReq, endpointGroup.selectNow(ctx));
        } else {
            derived = ctx.newDerivedContext(id, req, rpcReq, ctx.endpoint());
        }

        final RequestLogAccess parentLog = ctx.log();
        final RequestLog partial = parentLog.partial();
        final RequestLogBuilder logBuilder = derived.logBuilder();
        // serializationFormat is always not null, so this is fine.
        logBuilder.serializationFormat(partial.serializationFormat());
        if (parentLog.isAvailable(RequestLogProperty.NAME)) {
            final String serviceName = partial.serviceName();
            final String name = partial.name();
            if (serviceName != null) {
                logBuilder.name(serviceName, name);
            } else {
                logBuilder.name(name);
            }
        }

        final RequestLogBuilder parentLogBuilder = ctx.logBuilder();
        if (parentLogBuilder.isDeferred(RequestLogProperty.REQUEST_CONTENT)) {
            logBuilder.defer(RequestLogProperty.REQUEST_CONTENT);
        }
        parentLog.whenAvailable(RequestLogProperty.REQUEST_CONTENT)
                 .thenAccept(requestLog -> logBuilder.requestContent(
                         requestLog.requestContent(), requestLog.rawRequestContent()));
        if (parentLogBuilder.isDeferred(RequestLogProperty.REQUEST_CONTENT_PREVIEW)) {
            logBuilder.defer(RequestLogProperty.REQUEST_CONTENT_PREVIEW);
        }
        parentLog.whenAvailable(RequestLogProperty.REQUEST_CONTENT_PREVIEW)
                 .thenAccept(requestLog -> logBuilder.requestContentPreview(
                         requestLog.requestContentPreview()));

        // Propagates the response content only when deferResponseContent is called.
        if (parentLogBuilder.isDeferred(RequestLogProperty.RESPONSE_CONTENT)) {
            logBuilder.defer(RequestLogProperty.RESPONSE_CONTENT);
            parentLog.whenAvailable(RequestLogProperty.RESPONSE_CONTENT)
                     .thenAccept(requestLog -> logBuilder.responseContent(
                             requestLog.responseContent(), requestLog.rawResponseContent()));
        }
        if (parentLogBuilder.isDeferred(RequestLogProperty.RESPONSE_CONTENT_PREVIEW)) {
            logBuilder.defer(RequestLogProperty.RESPONSE_CONTENT_PREVIEW);
            parentLog.whenAvailable(RequestLogProperty.RESPONSE_CONTENT_PREVIEW)
                     .thenAccept(requestLog -> logBuilder.responseContentPreview(
                             requestLog.responseContentPreview()));
        }
        ctx.logBuilder().addChild(derived.log());
        return derived;
    }

    private ClientUtil() {}
}
