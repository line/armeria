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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;
import static com.linecorp.armeria.internal.client.RedirectingClientUtil.allowAllDomains;
import static com.linecorp.armeria.internal.client.RedirectingClientUtil.allowSameDomain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.redirect.CyclicRedirectsException;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.client.redirect.TooManyRedirectsException;
import com.linecorp.armeria.client.redirect.UnexpectedDomainRedirectException;
import com.linecorp.armeria.client.redirect.UnexpectedProtocolRedirectException;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.internal.client.ClientUtil;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.util.NetUtil;

final class RedirectingClient extends SimpleDecoratingHttpClient {

    private static final Set<HttpStatus> redirectStatuses =
            ImmutableSet.of(HttpStatus.MOVED_PERMANENTLY, HttpStatus.FOUND, HttpStatus.SEE_OTHER,
                            HttpStatus.TEMPORARY_REDIRECT);

    private static final Set<SessionProtocol> httpAndHttps =
            Sets.immutableEnumSet(SessionProtocol.HTTP, SessionProtocol.HTTPS);

    static Function<? super HttpClient, RedirectingClient> newDecorator(
            ClientBuilderParams params, RedirectConfig redirectConfig) {
        final boolean undefinedUri = Clients.isUndefinedUri(params.uri());
        final Set<SessionProtocol> allowedProtocols =
                allowedProtocols(undefinedUri, redirectConfig.allowedProtocols(),
                                 params.scheme().sessionProtocol());
        final BiPredicate<ClientRequestContext, String> domainFilter =
                domainFilter(undefinedUri, redirectConfig.domainFilter());
        return delegate -> new RedirectingClient(delegate, allowedProtocols, domainFilter,
                                                 redirectConfig.maxRedirects());
    }

    private static Set<SessionProtocol> allowedProtocols(boolean undefinedUri,
                                                         @Nullable Set<SessionProtocol> allowedProtocols,
                                                         SessionProtocol usedProtocol) {
        if (undefinedUri) {
            if (allowedProtocols != null) {
                return allowedProtocols;
            }
            return httpAndHttps;
        }
        final ImmutableSet.Builder<SessionProtocol> builder = ImmutableSet.builderWithExpectedSize(2);
        if (allowedProtocols != null) {
            builder.addAll(allowedProtocols);
        } else {
            // We always add HTTPS if allowedProtocols is not specified by a user.
            builder.add(SessionProtocol.HTTPS);
        }
        if (usedProtocol.isHttp()) {
            builder.add(SessionProtocol.HTTP);
        } else if (usedProtocol.isHttps()) {
            builder.add(SessionProtocol.HTTPS);
        }
        return builder.build();
    }

    private static BiPredicate<ClientRequestContext, String> domainFilter(
            boolean undefinedUri,
            @Nullable BiPredicate<ClientRequestContext, String> domainFilter) {
        if (domainFilter != null) {
            return domainFilter;
        }
        if (undefinedUri) {
            return allowAllDomains;
        }
        return allowSameDomain;
    }

    private final Set<SessionProtocol> allowedProtocols;
    private final BiPredicate<ClientRequestContext, String> domainFilter;
    private final int maxRedirects;

    RedirectingClient(HttpClient delegate, Set<SessionProtocol> allowedProtocols,
                      BiPredicate<ClientRequestContext, String> domainFilter, int maxRedirects) {
        super(delegate);
        this.allowedProtocols = allowedProtocols;
        this.domainFilter = domainFilter;
        this.maxRedirects = maxRedirects;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.from(responseFuture, ctx.eventLoop());
        final HttpRequestDuplicator reqDuplicator = req.toDuplicator(ctx.eventLoop().withoutContext(), 0);
        final RedirectContext redirectCtx = new RedirectContext(ctx, req, res, responseFuture);
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
                final Throwable abortCause;
                if (cause != null) {
                    abortCause = cause;
                } else {
                    abortCause = AbortedStreamException.get();
                }
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
                @Nullable
                final Throwable cause = log.responseCause();
                if (cause != null) {
                    abortResponse(response, derivedCtx, cause);
                    handleException(ctx, reqDuplicator, responseFuture, cause, false);
                    return;
                }
            }

            final ResponseHeaders responseHeaders = log.responseHeaders();
            if (!redirectStatuses.contains(responseHeaders.status())) {
                endRedirect(ctx, reqDuplicator, responseFuture, response);
                return;
            }
            @Nullable
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
                    final SessionProtocol redirectProtocol = Scheme.parse(redirectUri.getScheme())
                                                                   .sessionProtocol();
                    if (!allowedProtocols.contains(redirectProtocol)) {
                        handleException(ctx, derivedCtx, reqDuplicator, responseFuture, response,
                                        UnexpectedProtocolRedirectException.of(
                                                redirectProtocol, allowedProtocols));
                        return;
                    }

                    if (!domainFilter.test(ctx, redirectUri.getHost())) {
                        handleException(ctx, derivedCtx, reqDuplicator, responseFuture, response,
                                        UnexpectedDomainRedirectException.of(redirectUri.getHost()));
                        return;
                    }
                }
            } catch (Throwable t) {
                handleException(ctx, derivedCtx, reqDuplicator, responseFuture, response, t);
                return;
            }

            final HttpRequestDuplicator newReqDuplicator =
                    newReqDuplicator(reqDuplicator, responseHeaders, requestHeaders, redirectUri.toString());

            final String redirectFullUri;
            try {
                redirectFullUri = buildFullUri(ctx, redirectUri, newReqDuplicator.headers());
            } catch (Throwable t) {
                handleException(ctx, derivedCtx, reqDuplicator, responseFuture, response, t);
                return;
            }

            if (isCyclicRedirects(redirectCtx, redirectFullUri, newReqDuplicator.headers())) {
                handleException(ctx, derivedCtx, reqDuplicator, responseFuture, response,
                                CyclicRedirectsException.of(redirectCtx.originalUri(),
                                                            redirectCtx.redirectUris().values()));
                return;
            }

            final Multimap<HttpMethod, String> redirectUris = redirectCtx.redirectUris();
            if (redirectUris.size() > maxRedirects) {
                handleException(ctx, derivedCtx, reqDuplicator, responseFuture,
                                response, TooManyRedirectsException.of(maxRedirects, redirectCtx.originalUri(),
                                                                       redirectUris.values()));
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

    private static void endRedirect(ClientRequestContext ctx, HttpRequestDuplicator reqDuplicator,
                                    CompletableFuture<HttpResponse> responseFuture,
                                    HttpResponse response) {
        ctx.logBuilder().endResponseWithLastChild();
        responseFuture.complete(response);
        reqDuplicator.close();
    }

    private static void handleException(ClientRequestContext ctx, ClientRequestContext derivedCtx,
                                        HttpRequestDuplicator reqDuplicator,
                                        CompletableFuture<HttpResponse> future, HttpResponse originalRes,
                                        Throwable cause) {
        abortResponse(originalRes, derivedCtx, cause);
        handleException(ctx, reqDuplicator, future, cause, false);
    }

    private static void handleException(ClientRequestContext ctx, HttpRequestDuplicator reqDuplicator,
                                        CompletableFuture<HttpResponse> future, Throwable cause,
                                        boolean initialAttempt) {
        future.completeExceptionally(cause);
        reqDuplicator.abort(cause);
        if (initialAttempt) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);
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

    private static String buildFullUri(ClientRequestContext ctx, URI redirectUri, RequestHeaders newHeaders)
            throws URISyntaxException {
        // Build the full uri so we don't consider the situation, which session protocol or port is changed,
        // as a cyclic redirects.
        if (redirectUri.isAbsolute()) {
            if (redirectUri.getPort() > 0) {
                return redirectUri.toString();
            }
            final int port;
            if (redirectUri.getScheme().startsWith("https")) {
                port = SessionProtocol.HTTPS.defaultPort();
            } else {
                port = SessionProtocol.HTTP.defaultPort();
            }
            return new URI(redirectUri.getScheme(), redirectUri.getRawUserInfo(), redirectUri.getHost(), port,
                           redirectUri.getRawPath(), redirectUri.getRawQuery(), redirectUri.getRawFragment())
                    .toString();
        }
        return buildUri(ctx, newHeaders);
    }

    private static boolean isCyclicRedirects(RedirectContext redirectCtx, String redirectUri,
                                             RequestHeaders newHeaders) {
        final boolean added = redirectCtx.addRedirectUri(newHeaders.method(), redirectUri);
        if (!added) {
            return true;
        }

        return redirectCtx.originalUri().equals(redirectUri) &&
               redirectCtx.request().method() == newHeaders.method();
    }

    private static String buildUri(ClientRequestContext ctx, RequestHeaders headers) {
        final String originalUri;
        try (TemporaryThreadLocals threadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder sb = threadLocals.stringBuilder();
            if (ctx.sessionProtocol().isHttp()) {
                sb.append(SessionProtocol.HTTP.uriText());
            } else {
                sb.append(SessionProtocol.HTTPS.uriText());
            }
            sb.append("://");
            @Nullable
            String authority = headers.authority();
            final Endpoint endpoint = ctx.endpoint();
            assert endpoint != null;
            if (authority == null) {
                authority = endpoint.authority();
            }
            setAuthorityAndPort(ctx, endpoint, sb, authority);
            sb.append(headers.path());
            originalUri = sb.toString();
        }
        return originalUri;
    }

    private static void setAuthorityAndPort(ClientRequestContext ctx, Endpoint endpoint, StringBuilder sb,
                                            String authority) {
        // Add port number as well so that we don't raise a CyclicRedirectsException when the port is
        // different.

        if (authority.charAt(0) == '[') {
            final int closingBracketPos = authority.lastIndexOf(']');
            if (closingBracketPos < 0) {
                // Should never reach here because we already validate the authority.
                throw new IllegalStateException("Invalid authority: " + authority);
            }
            sb.append(authority);
            if (authority.indexOf(':', closingBracketPos) < 0) {
                // The authority does not have port number so we add it.
                addPort(ctx, endpoint, sb);
            }
            return;
        }

        if (NetUtil.isValidIpV6Address(authority)) {
            sb.append('[');
            sb.append(authority);
            sb.append(']');
            addPort(ctx, endpoint, sb);
            return;
        }

        sb.append(authority);
        if (authority.lastIndexOf(':') < 0) {
            addPort(ctx, endpoint, sb);
        }
    }

    private static void addPort(ClientRequestContext ctx, Endpoint endpoint, StringBuilder sb) {
        sb.append(':');
        sb.append(endpoint.port(ctx.sessionProtocol().defaultPort()));
    }

    @VisibleForTesting
    static class RedirectContext {

        private final ClientRequestContext ctx;
        private final HttpRequest request;
        private final CompletableFuture<Void> responseWhenComplete;
        private final CompletableFuture<HttpResponse> responseFuture;
        @Nullable
        private Multimap<HttpMethod, String> redirectUris;
        @Nullable
        private String originalUri;

        RedirectContext(ClientRequestContext ctx, HttpRequest request,
                        HttpResponse response, CompletableFuture<HttpResponse> responseFuture) {
            this.ctx = ctx;
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

        String originalUri() {
            if (originalUri == null) {
                originalUri = buildUri(ctx, request.headers());
            }
            return originalUri;
        }

        boolean addRedirectUri(HttpMethod method, String redirectUri) {
            if (redirectUris == null) {
                redirectUris = LinkedListMultimap.create();
            }
            return redirectUris.put(method, redirectUri);
        }

        Multimap<HttpMethod, String> redirectUris() {
            // Always called after addRedirectUri is called.
            assert redirectUris != null;
            return redirectUris;
        }
    }
}
