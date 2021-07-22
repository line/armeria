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
package com.linecorp.armeria.client;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.client.DefaultWebClient.pathWithQuery;
import static com.linecorp.armeria.client.RedirectConfigBuilder.allowSameDomain;
import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.internal.client.ClientUtil;

final class RedirectingClient extends SimpleDecoratingHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(RedirectingClient.class);

    private final RedirectConfig redirectConfig;
    private final boolean withBaseUri;

    RedirectingClient(HttpClient delegate, RedirectConfig redirectConfig, boolean withBaseUri) {
        super(delegate);
        this.redirectConfig = redirectConfig;
        this.withBaseUri = withBaseUri;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.from(responseFuture, ctx.eventLoop());
        final HttpRequestDuplicator reqDuplicator = req.toDuplicator(ctx.eventLoop().withoutContext(), 0);
        final RedirectContext redirectCtx = new RedirectContext(req, res, responseFuture);
        execute0(ctx, redirectCtx, reqDuplicator, true);
        return res;
    }

    private void execute0(ClientRequestContext ctx, RedirectContext redirectCtx,
                          HttpRequestDuplicator reqDuplicator, boolean initialAttempt) {
        final CompletableFuture<Void> originalReqWhenComplete = redirectCtx.request().whenComplete();
        final CompletableFuture<HttpResponse> responseFuture = redirectCtx.responseFuture();
        if (originalReqWhenComplete.isCompletedExceptionally()) {
            originalReqWhenComplete.exceptionally(cause -> {
                handleException(ctx, reqDuplicator, responseFuture, cause, initialAttempt);
                return null;
            });
            return;
        }

        if (redirectCtx.responseWhenComplete().isDone()) {
            redirectCtx.responseWhenComplete().handle((result, cause) -> {
                final Throwable abortCause = firstNonNull(cause, AbortedStreamException.get());
                handleException(ctx, reqDuplicator, responseFuture, abortCause, initialAttempt);
                return null;
            });
            return;
        }

        final HttpRequest duplicateReq = reqDuplicator.duplicate();
        final ClientRequestContext derivedCtx;
        try {
            derivedCtx = ClientUtil.newDerivedContext(ctx, duplicateReq, ctx.rpcRequest(), initialAttempt);
        } catch (Throwable t) {
            handleException(ctx, reqDuplicator, responseFuture, t, initialAttempt);
            return;
        }

        final HttpResponse response = executeWithFallback(unwrap(), derivedCtx,
                                                          (context, cause) -> HttpResponse.ofFailure(cause));
        derivedCtx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).thenAccept(log -> {
            if (log.isAvailable(RequestLogProperty.RESPONSE_CAUSE)) {
                final Throwable cause = log.responseCause();
                if (cause != null) {
                    abortResponse(response, derivedCtx, cause);
                    handleException(ctx, reqDuplicator, responseFuture, cause, false);
                    return;
                }
            }
            final ResponseHeaders responseHeaders = log.responseHeaders();
            final HttpStatus status = responseHeaders.status();
            if (status.codeClass() != HttpStatusClass.REDIRECTION ||
                status == HttpStatus.NOT_MODIFIED ||
                status == HttpStatus.USE_PROXY) {
                endRedirect(ctx, reqDuplicator, responseFuture, response);
                return;
            }
            final String location = responseHeaders.get(HttpHeaderNames.LOCATION);
            if (isNullOrEmpty(location)) {
                endRedirect(ctx, reqDuplicator, responseFuture, response);
                return;
            }

            final RequestHeaders requestHeaders = log.requestHeaders();
            final URI redirectUri;
            try {
                redirectUri = URI.create(requestHeaders.path()).resolve(location);
                if (redirectUri.isAbsolute()) {
                    final BiPredicate<ClientRequestContext, String> domainFilter =
                            redirectConfig.domainFilter();
                    if (domainFilter != null) {
                        if (!domainFilter.test(ctx, redirectUri.getHost())) {
                            logger.info("Stop redirection because the domain is not allowed to redirect: {}",
                                        redirectUri.getHost());
                            endRedirect(ctx, reqDuplicator, responseFuture, response);
                            return;
                        }
                    } else if (withBaseUri && !allowSameDomain.test(ctx, redirectUri.getHost())) {
                        logger.info("Stop redirection because the domain is not allowed to redirect: {}",
                                    redirectUri.getHost());
                        endRedirect(ctx, reqDuplicator, responseFuture, response);
                        return;
                    }
                }
            } catch (Throwable t) {
                abortResponse(response, derivedCtx, t);
                handleException(ctx, reqDuplicator, responseFuture, t, false);
                return;
            }

            final HttpRequestDuplicator newReqDuplicator =
                    newReqDuplicator(reqDuplicator, responseHeaders, requestHeaders, redirectUri.toString());
            if (isRedirectLoops(ctx, redirectCtx, redirectUri, newReqDuplicator.headers())) {
                final RedirectLoopsException exception = redirectLoopsException(redirectCtx);
                abortResponse(response, derivedCtx, exception);
                handleException(ctx, newReqDuplicator, responseFuture, exception, false);
                return;
            }

            final Multimap<HttpMethod, String> redirectPaths = redirectCtx.redirectPaths();
            assert redirectPaths != null;
            if (redirectPaths.size() > redirectConfig.maxRedirects()) {
                logger.info("Stop redirection because the number of redirection exceeds the limit: {}",
                             redirectConfig.maxRedirects());
                endRedirect(ctx, newReqDuplicator, responseFuture, response);
                return;
            }

            abortResponse(response, derivedCtx, null);
            ctx.eventLoop().execute(() -> execute0(ctx, redirectCtx, newReqDuplicator, false));
        });
    }

    private static HttpRequestDuplicator newReqDuplicator(HttpRequestDuplicator reqDuplicator,
                                                          ResponseHeaders responseHeaders,
                                                          RequestHeaders requestHeaders, String newUriString) {
        final RequestHeadersBuilder builder = requestHeaders.toBuilder();
        builder.path(newUriString);
        final HttpMethod method = requestHeaders.method();
        if (responseHeaders.status() == HttpStatus.SEE_OTHER &&
            !(method == HttpMethod.GET || method == HttpMethod.HEAD)) {
            // HTTP methods are changed to GET when the status is 303.
            // https://developer.mozilla.org/en-US/docs/Web/HTTP/Redirections
            // https://datatracker.ietf.org/doc/html/rfc7231#section-6.4.4
            builder.method(HttpMethod.GET);
            reqDuplicator.abort();
            // TODO(minwoox): implement https://github.com/line/armeria/issues/1409
            return HttpRequest.of(builder.build()).toDuplicator();
        } else {
            return new HttpRequestDuplicatorWrapper(reqDuplicator, builder.build());
        }
    }

    private static boolean isRedirectLoops(ClientRequestContext ctx, RedirectContext redirectCtx, URI newUri,
                                           RequestHeaders newHeaders) {
        final String newPath = pathWithQuery(newUri, newUri.getRawQuery());
        final HttpRequest originalRequest = redirectCtx.request();
        if (originalRequest.path().equals(newPath) && originalRequest.method() == newHeaders.method()) {
            final String newHost = newUri.getHost();
            final Endpoint endpoint = ctx.endpoint();
            // endpoint is not null because we already received the response.
            assert endpoint != null;
            if (newHost == null || newHost.equals(endpoint.host())) {
                return true;
            }
        }
        return !redirectCtx.addRedirectPath(newUri.toString(), newHeaders.method());
    }

    private static RedirectLoopsException redirectLoopsException(RedirectContext redirectCtx) {
        final String originalPath = redirectCtx.request.path();
        final Multimap<HttpMethod, String> redirectPaths = redirectCtx.redirectPaths();
        final RedirectLoopsException exception;
        if (redirectPaths == null) {
            exception = new RedirectLoopsException(originalPath);
        } else {
            exception = new RedirectLoopsException(originalPath, redirectPaths.values());
        }
        return exception;
    }

    private static void handleException(ClientRequestContext ctx, HttpRequestDuplicator reqDuplicator,
                                        CompletableFuture<HttpResponse> future, Throwable cause,
                                        boolean endRequestLog) {
        future.completeExceptionally(cause);
        reqDuplicator.abort(cause);
        if (endRequestLog) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);
    }

    private static void endRedirect(ClientRequestContext ctx, HttpRequestDuplicator reqDuplicator,
                                    CompletableFuture<HttpResponse> responseFuture,
                                    HttpResponse response) {
        ctx.logBuilder().endResponseWithLastChild();
        responseFuture.complete(response);
        reqDuplicator.close();
    }

    private static void abortResponse(HttpResponse originalRes, ClientRequestContext derivedCtx,
                                      @Nullable Throwable cause) {
        // Set response content with null to make sure that the log is complete.
        final RequestLogBuilder logBuilder = derivedCtx.logBuilder();
        logBuilder.responseContent(null, null);
        logBuilder.responseContentPreview(null);

        if (cause != null) {
            originalRes.abort(cause);
        } else {
            originalRes.abort();
        }
    }

    private static class RedirectContext {

        private final HttpRequest request;
        private final CompletableFuture<Void> responseWhenComplete;
        private final CompletableFuture<HttpResponse> responseFuture;
        @Nullable
        private Multimap<HttpMethod, String> redirectPaths;

        RedirectContext(HttpRequest request, HttpResponse response,
                        CompletableFuture<HttpResponse> responseFuture) {
            this.request = request;
            responseWhenComplete = response.whenComplete();
            this.responseFuture = responseFuture;
        }

        HttpRequest request() {
            return request;
        }

        CompletableFuture<Void> responseWhenComplete() {
            return responseWhenComplete;
        }

        CompletableFuture<HttpResponse> responseFuture() {
            return responseFuture;
        }

        boolean addRedirectPath(String redirectPath, HttpMethod method) {
            if (redirectPaths == null) {
                redirectPaths = HashMultimap.create();
            }
            return redirectPaths.put(method, redirectPath);
        }

        @Nullable
        Multimap<HttpMethod, String> redirectPaths() {
            return redirectPaths;
        }
    }
}
