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
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.findAuthority;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.redirect.CyclicRedirectsException;
import com.linecorp.armeria.client.redirect.RedirectConfig;
import com.linecorp.armeria.client.redirect.TooManyRedirectsException;
import com.linecorp.armeria.client.redirect.UnexpectedDomainRedirectException;
import com.linecorp.armeria.client.redirect.UnexpectedProtocolRedirectException;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RequestTargetForm;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.internal.client.AggregatedHttpRequestDuplicator;
import com.linecorp.armeria.internal.client.ClientUtil;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.netty.util.NetUtil;

final class RedirectingClient extends SimpleDecoratingHttpClient {

    private static final Set<HttpStatus> redirectStatuses =
            ImmutableSet.of(HttpStatus.MOVED_PERMANENTLY, HttpStatus.FOUND, HttpStatus.SEE_OTHER,
                            HttpStatus.TEMPORARY_REDIRECT);

    private static final Set<SessionProtocol> httpAndHttps =
            Sets.immutableEnumSet(SessionProtocol.HTTP, SessionProtocol.HTTPS);

    private static final Splitter pathSplitter = Splitter.on('/');

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
        final HttpResponse res = HttpResponse.of(responseFuture, ctx.eventLoop());
        final RedirectContext redirectCtx = new RedirectContext(ctx, req, res, responseFuture);
        if (ctx.exchangeType().isRequestStreaming()) {
            final HttpRequestDuplicator reqDuplicator = req.toDuplicator(ctx.eventLoop().withoutContext(), 0);
            execute0(ctx, redirectCtx, reqDuplicator, true);
        } else {
            req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop()))
               .handle((agg, cause) -> {
                   if (cause != null) {
                       handleException(ctx, null, responseFuture, cause, true);
                   } else {
                       final HttpRequestDuplicator reqDuplicator = new AggregatedHttpRequestDuplicator(agg);
                       execute0(ctx, redirectCtx, reqDuplicator, true);
                   }
                   return null;
               });
        }
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
            final String location = responseHeaders.get(HttpHeaderNames.LOCATION);
            if (isNullOrEmpty(location)) {
                endRedirect(ctx, reqDuplicator, responseFuture, response);
                return;
            }

            final RequestHeaders requestHeaders = log.requestHeaders();

            // Resolve the actual redirect location.
            final RequestTarget nextReqTarget = resolveLocation(ctx, location);
            if (nextReqTarget == null) {
                handleException(ctx, derivedCtx, reqDuplicator, responseFuture, response,
                                new IllegalArgumentException("Invalid redirect location: " + location));
                return;
            }

            final String nextScheme = nextReqTarget.scheme();
            final String nextAuthority = nextReqTarget.authority();
            final String nextHost = nextReqTarget.host();
            assert nextReqTarget.form() == RequestTargetForm.ABSOLUTE &&
                   nextScheme != null && nextAuthority != null && nextHost != null
                    : "resolveLocation() must return an absolute request target: " + nextReqTarget;

            try {
                // Reject if:
                // 1) the protocol is not same with the original one; and
                // 2) the protocol is not in the allow-list.
                final SessionProtocol nextProtocol = SessionProtocol.of(nextScheme);
                if (ctx.sessionProtocol() != nextProtocol &&
                    !allowedProtocols.contains(nextProtocol)) {
                    handleException(ctx, derivedCtx, reqDuplicator, responseFuture, response,
                                    UnexpectedProtocolRedirectException.of(
                                            nextProtocol, allowedProtocols));
                    return;
                }

                // Reject if:
                // 1) the host is not same with the original one; and
                // 2) the host does not pass the domain filter.
                if (!nextHost.equals(ctx.host()) &&
                    !domainFilter.test(ctx, nextHost)) {
                    handleException(ctx, derivedCtx, reqDuplicator, responseFuture, response,
                                    UnexpectedDomainRedirectException.of(nextHost));
                    return;
                }
            } catch (Throwable t) {
                handleException(ctx, derivedCtx, reqDuplicator, responseFuture, response, t);
                return;
            }

            final HttpRequestDuplicator newReqDuplicator =
                    newReqDuplicator(reqDuplicator, responseHeaders, requestHeaders,
                                     nextReqTarget.toString(), nextAuthority);

            try {
                redirectCtx.validateRedirects(nextReqTarget,
                                              newReqDuplicator.headers().method(),
                                              maxRedirects);
            } catch (Throwable t) {
                handleException(ctx, derivedCtx, reqDuplicator, responseFuture, response, t);
                return;
            }

            // Drain the response to release the pooled objects.
            response.subscribe(ctx.eventLoop()).handleAsync((unused, cause) -> {
                if (cause != null) {
                    handleException(ctx, derivedCtx, reqDuplicator, responseFuture, response, cause);
                    return null;
                }
                execute0(ctx, redirectCtx, newReqDuplicator, false);
                return null;
            }, ctx.eventLoop());
        });
    }

    @Nullable
    @VisibleForTesting
    static RequestTarget resolveLocation(ClientRequestContext ctx, String location) {
        final long length = location.length();
        assert length > 0;

        final String resolvedUri;
        if (location.charAt(0) == '/') {
            if (length > 1 && location.charAt(1) == '/') {
                // No scheme, e.g. //foo.com/bar
                resolvedUri = ctx.sessionProtocol().uriText() + ':' + location;
            } else {
                // No scheme, no authority, e.g. /bar
                resolvedUri = ctx.sessionProtocol().uriText() + "://" + ctx.authority() + location;
            }
        } else {
            final int authorityIdx = findAuthority(location);
            if (authorityIdx < 0) {
                // A relative path, e.g. ./bar
                resolvedUri = resolveRelativeLocation(ctx, location);
                if (resolvedUri == null) {
                    return null;
                }
            } else {
                // A full absolute URI, e.g. http://foo.com/bar
                // Note that we should normalize an explicit scheme such as `h1c` into `http` or `https`,
                // because otherwise a potentially malicious peer can force us to use inefficient protocols
                // like HTTP/1.
                final SessionProtocol proto = SessionProtocol.find(location.substring(0, authorityIdx - 3));
                if (proto != null) {
                    switch (proto) {
                        case HTTP:
                        case HTTPS:
                            resolvedUri = location;
                            break;
                        default:
                            if (proto.isHttp()) {
                                resolvedUri = "http://" + location.substring(authorityIdx);
                            } else if (proto.isHttps()) {
                                resolvedUri = "https://" + location.substring(authorityIdx);
                            } else {
                                return null;
                            }
                    }
                } else {
                    // Unknown scheme.
                    return null;
                }
            }
        }

        return RequestTarget.forClient(resolvedUri);
    }

    @Nullable
    private static String resolveRelativeLocation(ClientRequestContext ctx, String location) {
        final String originalPath = ctx.path();

        // Find the base path, e.g.
        // - /foo     -> /
        // - /foo/    -> /foo/
        // - /foo/bar -> /foo/
        final int lastSlashIdx = originalPath.lastIndexOf('/');
        assert lastSlashIdx >= 0 : "originalPath doesn't contain a slash: " + originalPath;

        // Generate the full path.
        final String fullPath = originalPath.substring(0, lastSlashIdx + 1) + location;
        final Iterator<String> it = pathSplitter.split(fullPath).iterator();
        // Splitter will always emit an empty string as the first component, so we skip it.
        assert it.hasNext() && it.next().isEmpty() : fullPath;

        // Resolve `.` and `..` from the full path.
        try (TemporaryThreadLocals tmp = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tmp.stringBuilder();
            buf.append(ctx.sessionProtocol().uriText()).append("://").append(ctx.authority());
            final int authorityEndIdx = buf.length();
            while (it.hasNext()) {
                final String component = it.next();
                switch (component) {
                    case ".":
                        if (!it.hasNext()) {
                            // Append '/' only when the '.' is the last component, e.g. /foo/. -> /foo/
                            buf.append('/');
                        }
                        break;
                    case "..":
                        final int idx = buf.lastIndexOf("/");
                        if (idx < authorityEndIdx) {
                            // Too few parents
                            return null;
                        }
                        if (it.hasNext()) {
                            // Don't keep the '/' because the next component will add it anyway,
                            // e.g. /foo/../bar -> /bar
                            buf.delete(idx, buf.length());
                        } else {
                            // Keep the last '/' if the '..' is the last component,
                            // e.g. /foo/bar/.. -> /foo/
                            buf.delete(idx + 1, buf.length());
                        }
                        break;
                    default:
                        buf.append('/').append(component);
                        break;
                }
            }

            return buf.toString();
        }
    }

    private static HttpRequestDuplicator newReqDuplicator(HttpRequestDuplicator reqDuplicator,
                                                          ResponseHeaders responseHeaders,
                                                          RequestHeaders requestHeaders,
                                                          String nextUri,
                                                          String nextAuthority) {

        final RequestHeadersBuilder builder = requestHeaders.toBuilder();
        builder.path(nextUri);
        builder.authority(nextAuthority);

        final HttpMethod method = requestHeaders.method();
        if (responseHeaders.status() == HttpStatus.SEE_OTHER &&
            !(method == HttpMethod.GET || method == HttpMethod.HEAD)) {
            // HTTP methods are changed to GET when the status is 303.
            // https://developer.mozilla.org/en-US/docs/Web/HTTP/Redirections
            // https://datatracker.ietf.org/doc/html/rfc7231#section-6.4.4
            builder.method(HttpMethod.GET);
            reqDuplicator.abort();
            // TODO(minwoox): implement https://github.com/line/armeria/issues/1409
            return new AggregatedHttpRequestDuplicator(AggregatedHttpRequest.of(builder.build()));
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

    private static void handleException(ClientRequestContext ctx, @Nullable HttpRequestDuplicator reqDuplicator,
                                        CompletableFuture<HttpResponse> future, Throwable cause,
                                        boolean initialAttempt) {
        future.completeExceptionally(cause);
        if (reqDuplicator != null) {
            reqDuplicator.abort(cause);
        }
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
            String authority = headers.authority();
            final Endpoint endpoint = ctx.endpoint();
            assert endpoint != null;
            if (authority == null) {
                authority = endpoint.authority();
            }
            appendAuthority(ctx, endpoint, sb, authority);
            sb.append(headers.path());
            originalUri = sb.toString();
        }
        return originalUri;
    }

    private static void appendAuthority(ClientRequestContext ctx, Endpoint endpoint, StringBuilder sb,
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
        private String originalUri;
        @Nullable
        private Set<RedirectSignature> redirectSignatures;

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

        void validateRedirects(RequestTarget nextReqTarget, HttpMethod nextMethod, int maxRedirects) {
            if (redirectSignatures == null) {
                redirectSignatures = new LinkedHashSet<>();

                final String originalProtocol = ctx.sessionProtocol().isTls() ? "https" : "http";
                final RedirectSignature originalSignature = new RedirectSignature(originalProtocol,
                                                                                  ctx.authority(),
                                                                                  request.headers().path(),
                                                                                  request.method());
                redirectSignatures.add(originalSignature);
            }

            final RedirectSignature signature = new RedirectSignature(nextReqTarget.scheme(),
                                                                      nextReqTarget.authority(),
                                                                      nextReqTarget.pathAndQuery(),
                                                                      nextMethod);
            if (!redirectSignatures.add(signature)) {
                throw CyclicRedirectsException.of(originalUri(), redirectUris());
            }

            // Minus 1 because the original signature is also included.
            if (redirectSignatures.size() - 1 > maxRedirects) {
                throw TooManyRedirectsException.of(maxRedirects, originalUri(), redirectUris());
            }
        }

        String originalUri() {
            if (originalUri == null) {
                originalUri = buildUri(ctx, request.headers());
            }
            return originalUri;
        }

        Set<String> redirectUris() {
            // Always called after addRedirectSignature is called.
            assert redirectSignatures != null;
            return redirectSignatures.stream()
                                     .map(RedirectSignature::uri)
                                     .collect(ImmutableSet.toImmutableSet());
        }
    }

    @VisibleForTesting
    static class RedirectSignature {
        private final String protocol;
        private final String authority;
        private final String pathAndQuery;
        private final HttpMethod method;

        RedirectSignature(String protocol, String authority, String pathAndQuery, HttpMethod method) {
            this.protocol = protocol;
            this.authority = authority;
            this.pathAndQuery = pathAndQuery;
            this.method = method;
        }

        @Override
        public int hashCode() {
            return Objects.hash(protocol, authority, pathAndQuery, method);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof RedirectSignature)) {
                return false;
            }

            final RedirectSignature that = (RedirectSignature) obj;
            return pathAndQuery.equals(that.pathAndQuery) &&
                   authority.equals(that.authority) &&
                   protocol.equals(that.protocol) &&
                   method == that.method;
        }

        String uri() {
            return protocol + "://" + authority + pathAndQuery;
        }
    }
}
