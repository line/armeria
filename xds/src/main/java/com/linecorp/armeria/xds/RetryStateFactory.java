/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RefusedStreamException;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryConfigBuilder;
import com.linecorp.armeria.client.retry.RetryConfigMapping;
import com.linecorp.armeria.client.retry.RetryDecision;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.xds.RouteEntryMatcher.HeaderMatcherImpl;
import com.linecorp.armeria.xds.internal.XdsCommonUtil;

import io.envoyproxy.envoy.config.route.v3.HeaderMatcher;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy.RateLimitedRetryBackOff;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy.ResetHeader;
import io.envoyproxy.envoy.config.route.v3.RetryPolicy.RetryBackOff;
import io.grpc.Status;
import io.netty.util.AsciiString;

final class RetryStateFactory {

    private static final Logger logger = LoggerFactory.getLogger(RetryStateFactory.class);

    private static final AsciiString REQUEST_HEADER_GRPC_RETRY_ON = HttpHeaderNames.of("x-envoy-retry-grpc-on");
    private static final AsciiString REQUEST_HEADER_RETRY_ON = HttpHeaderNames.of("x-envoy-retry-on");
    private static final AsciiString REQUEST_HEADER_MAX_RETRIES = HttpHeaderNames.of("x-envoy-max-retries");
    private static final AsciiString REQUEST_HEADER_RETRIABLE_STATUS_CODES =
            HttpHeaderNames.of("x-envoy-retriable-status-codes");
    private static final AsciiString REQUEST_HEADER_RETRIABLE_HEADER_NAMES =
            HttpHeaderNames.of("x-envoy-retriable-header-names");
    private static final AsciiString RESPONSE_HEADER_X_ENVOY_RATELIMITED =
            HttpHeaderNames.of("x-envoy-ratelimited");
    private static final AsciiString RESPONSE_HEADER_GRPC_STATUS = HttpHeaderNames.of("grpc-status");

    private static final Set<AsciiString> REQUEST_RETRY_HEADER_NAMES =
            ImmutableSet.of(REQUEST_HEADER_GRPC_RETRY_ON, REQUEST_HEADER_RETRY_ON,
                            REQUEST_HEADER_MAX_RETRIES, REQUEST_HEADER_RETRIABLE_STATUS_CODES,
                            REQUEST_HEADER_RETRIABLE_HEADER_NAMES);

    private final List<HeaderMatcherImpl> retriableRequestHeadersMatchers;
    private final RetryStateImpl defaultRetryState;
    private final RetryConfig<HttpResponse> defaultRetryConfig;

    RetryStateFactory(RetryPolicy retryPolicy) {
        final Set<RetryPolicyTypes> policies = parseRetryOn(retryPolicy.getRetryOn());
        final List<HeaderMatcherImpl> retriableResponseHeaderMatchers =
                retryPolicy.getRetriableHeadersList().stream().map(HeaderMatcherImpl::new)
                           .collect(ImmutableList.toImmutableList());
        retriableRequestHeadersMatchers = retryPolicy.getRetriableRequestHeadersList().stream()
                                                     .map(HeaderMatcherImpl::new)
                                                     .collect(ImmutableList.toImmutableList());
        final Set<Integer> retriableStatusCodes =
                ImmutableSet.copyOf(retryPolicy.getRetriableStatusCodesList());
        final int numRetries = XdsCommonUtil.uint32ValueToInt(retryPolicy.getNumRetries(), -1);
        defaultRetryState = new RetryStateImpl(retryPolicy, policies, numRetries, retriableStatusCodes,
                                               retriableResponseHeaderMatchers);
        defaultRetryConfig = createRetryConfig(defaultRetryState);
    }

    private static RetryConfig<HttpResponse> createRetryConfig(RetryStateImpl retryState) {
        final RetryConfigBuilder<HttpResponse> builder = RetryConfig.builder(retryState);
        if (retryState.perTryTimeoutMillis > 0) {
            builder.responseTimeoutMillisForEachAttempt(retryState.perTryTimeoutMillis);
        }
        if (retryState.numRetries > 0) {
            builder.maxTotalAttempts(retryState.numRetries + 1);
        }
        return builder.build();
    }

    Function<? super HttpClient, RetryingClient> retryingDecorator() {
        final RetryConfigMapping<HttpResponse> mapping = (ctx, req0) -> {
            final HttpRequest req = (HttpRequest) req0;
            for (AsciiString headerName: REQUEST_RETRY_HEADER_NAMES) {
                if (req.headers().contains(headerName)) {
                    final RetryStateImpl retryState = createRetryState(req.headers(), defaultRetryState,
                                                                       retriableRequestHeadersMatchers);
                    return createRetryConfig(retryState);
                }
            }
            return defaultRetryConfig;
        };
        return RetryingClient.builderWithMapping(mapping).newDecorator();
    }

    private static RetryStateImpl createRetryState(RequestHeaders requestHeaders,
                                                   RetryStateImpl defaultRetryState,
                                                   List<HeaderMatcherImpl> retriableRequestHeadersMatchers) {
        Set<RetryPolicyTypes> policies = defaultRetryState.policies;

        policies = retryPoliciesFromRequestHeader(requestHeaders, retriableRequestHeadersMatchers, policies);

        int numRetries = defaultRetryState.numRetries;
        if (!policies.isEmpty()) {
            final Integer maxRetries = requestHeaders.getInt(REQUEST_HEADER_MAX_RETRIES);
            if (maxRetries != null) {
                numRetries = maxRetries;
            }
        }

        Set<Integer> retriableStatusCodes = defaultRetryState.retriableStatusCodes;
        final String headerStatusCodes = requestHeaders.get(REQUEST_HEADER_RETRIABLE_STATUS_CODES, "");
        if (!headerStatusCodes.isEmpty()) {
            final String[] splitStatusCodes = headerStatusCodes.split(",");
            final int expectedSize = splitStatusCodes.length + retriableStatusCodes.size();
            final ImmutableSet.Builder<Integer> builder = ImmutableSet.builderWithExpectedSize(expectedSize);
            for (String statusCodeStr : splitStatusCodes) {
                final Integer statusCode = XdsCommonUtil.simpleAtoi(statusCodeStr);
                if (statusCode == null) {
                    continue;
                }
                builder.add(statusCode);
            }
            builder.addAll(retriableStatusCodes);
            retriableStatusCodes = builder.build();
        }

        List<HeaderMatcherImpl> retriableResponseHeaderMatchers =
                defaultRetryState.retriableResponseHeaderMatchers;
        final String retriableHeaderNames = requestHeaders.get(REQUEST_HEADER_RETRIABLE_HEADER_NAMES, "");
        if (!retriableHeaderNames.isEmpty()) {
            final String[] splitHeaderNames = retriableHeaderNames.split(",");
            final int expectedSize = splitHeaderNames.length + retriableResponseHeaderMatchers.size();
            final ImmutableList.Builder<HeaderMatcherImpl> builder =
                    ImmutableList.builderWithExpectedSize(expectedSize);
            for (String headerName : splitHeaderNames) {
                builder.add(new HeaderMatcherImpl(HeaderMatcher.newBuilder()
                                                               .setName(headerName.trim()).build()));
            }
            builder.addAll(retriableResponseHeaderMatchers);
            retriableResponseHeaderMatchers = builder.build();
        }

        return new RetryStateImpl(defaultRetryState.retryPolicy, policies, numRetries, retriableStatusCodes,
                                  retriableResponseHeaderMatchers);
    }

    private static Set<RetryPolicyTypes> retryPoliciesFromRequestHeader(
            RequestHeaders requestHeaders, List<HeaderMatcherImpl> retriableRequestHeadersMatchers,
            Set<RetryPolicyTypes> policies) {
        if (!retriableRequestHeadersMatchers.isEmpty()) {
            boolean shouldRetry = false;
            for (HeaderMatcherImpl headerMatcher: retriableRequestHeadersMatchers) {
                if (headerMatcher.matches(requestHeaders)) {
                    shouldRetry = true;
                    break;
                }
            }
            if (!shouldRetry) {
                return ImmutableSet.of();
            }
        }

        final String retryOn = requestHeaders.get(REQUEST_HEADER_RETRY_ON, "");
        final String grpcRetryOn = requestHeaders.get(REQUEST_HEADER_GRPC_RETRY_ON, "");
        if (retryOn.isEmpty() && grpcRetryOn.isEmpty()) {
            return policies;
        }

        final ImmutableSet.Builder<RetryPolicyTypes> newPolicies = ImmutableSet.builder();
        newPolicies.addAll(policies);
        if (!retryOn.isEmpty()) {
            newPolicies.addAll(parseRetryOn(retryOn));
        }
        if (!grpcRetryOn.isEmpty()) {
            newPolicies.addAll(parseRetryOn(grpcRetryOn));
        }
        return Sets.immutableEnumSet(newPolicies.build());
    }

    private static Set<RetryPolicyTypes> parseRetryOn(String retryOn) {
        if (retryOn.isEmpty()) {
            return ImmutableSet.of();
        }
        final ImmutableSet.Builder<RetryPolicyTypes> policies = ImmutableSet.builder();
        for (String policyName: retryOn.split(",")) {
            final RetryPolicyTypes policy = RetryPolicyTypes.fromPolicyName(policyName);
            if (policy != null) {
                policies.add(policy);
            } else {
                logger.warn("Ignoring unknown retry policy: {}.", policyName);
            }
        }
        return Sets.immutableEnumSet(policies.build());
    }

    private static class RetryStateImpl implements RetryRule {

        private static final ResponseHeaders EMPTY_RESPONSE_HEADERS = ResponseHeaders.of(0);

        private final RetryPolicy retryPolicy;
        private final int numRetries;
        private final DelegatingBackoff backoff;
        private final Set<RetryPolicyTypes> policies;
        private final Set<Integer> retriableStatusCodes;
        private final List<HeaderMatcherImpl> retriableResponseHeaderMatchers;
        private final RetryDecision shouldRetry;
        private final long perTryTimeoutMillis;

        RetryStateImpl(RetryPolicy retryPolicy, Set<RetryPolicyTypes> policies,
                       int numRetries, Set<Integer> retriableStatusCodes,
                       List<HeaderMatcherImpl> retriableResponseHeaderMatchers) {
            this.retryPolicy = retryPolicy;
            this.numRetries = numRetries;
            final RetryBackOff retryBackOff = retryPolicy.getRetryBackOff();
            final long baseIntervalMillis =
                    XdsCommonUtil.durationToMillis(retryBackOff.getBaseInterval(), 25);
            final long maxIntervalMillis =
                    XdsCommonUtil.durationToMillis(retryBackOff.getMaxInterval(), baseIntervalMillis * 10);
            final Backoff defaultBackoff = Backoff.builderForExponential()
                                                  .initialDelayMillis(baseIntervalMillis)
                                                  .maxDelayMillis(maxIntervalMillis)
                                                  .jitter(0, 0.5)
                                                  .build();
            backoff = new DelegatingBackoff(defaultBackoff);
            shouldRetry = RetryDecision.retry(backoff);
            this.policies = policies;
            this.retriableStatusCodes = retriableStatusCodes;
            this.retriableResponseHeaderMatchers = retriableResponseHeaderMatchers;
            perTryTimeoutMillis = XdsCommonUtil.durationToMillis(retryPolicy.getPerTryTimeout(), -1);
        }

        private RetryDecision handleReset(ClientRequestContext ctx, Throwable cause) {
            if (policies.contains(RetryPolicyTypes.RETRY_ON_RESET)) {
                return shouldRetry;
            }
            if (!(cause instanceof UnprocessedRequestException)) {
                return RetryDecision.noRetry();
            }
            // Only UnprocessedRequestException related rules from now on
            if (policies.contains(RetryPolicyTypes.RETRY_ON_RESET_BEFORE_REQUEST)) {
                return shouldRetry;
            }
            final Throwable upeCause = cause.getCause();
            if (policies.contains(RetryPolicyTypes.RETRY_ON_REFUSED_STREAM)) {
                if (upeCause instanceof RefusedStreamException) {
                    return shouldRetry;
                }
            }
            if (policies.contains(RetryPolicyTypes.RETRY_ON_CONNECT_FAILURE)) {
                final RequestLog log = ctx.log().getIfAvailable(RequestLogProperty.SESSION);
                if (log != null && log.channel() == null) {
                    return shouldRetry;
                }
            }
            return RetryDecision.noRetry();
        }

        private RetryDecision handleResponseHeaders(ResponseHeaders responseHeaders) {
            if (responseHeaders.contains(RESPONSE_HEADER_X_ENVOY_RATELIMITED)) {
                if (policies.contains(RetryPolicyTypes.RETRY_ON_ENVOY_RATE_LIMITED)) {
                    return shouldRetry;
                }
                return RetryDecision.noRetry();
            }

            final HttpStatus status = responseHeaders.status();
            if (policies.contains(RetryPolicyTypes.RETRY_ON_5XX)) {
                if (status.isServerError()) {
                    return shouldRetry;
                }
            }
            if (policies.contains(RetryPolicyTypes.RETRY_ON_GATEWAY_ERROR)) {
                if (status.code() >= 502 && status.code() < 505) {
                    return shouldRetry;
                }
            }
            if (policies.contains(RetryPolicyTypes.RETRY_ON_RETRIABLE_4XX)) {
                if (status.code() == HttpStatus.CONFLICT.code()) {
                    return shouldRetry;
                }
            }
            if (policies.contains(RetryPolicyTypes.RETRY_ON_RETRIABLE_STATUS_CODES)) {
                if (retriableStatusCodes.contains(status.code())) {
                    return shouldRetry;
                }
            }
            if (policies.contains(RetryPolicyTypes.RETRY_ON_RETRIABLE_HEADERS)) {
                for (HeaderMatcherImpl headerMatcher : retriableResponseHeaderMatchers) {
                    if (headerMatcher.matches(responseHeaders)) {
                        return shouldRetry;
                    }
                }
            }
            final Integer grpcStatus = responseHeaders.getInt(RESPONSE_HEADER_GRPC_STATUS);
            if (grpcStatus != null) {
                if (policies.contains(RetryPolicyTypes.RETRY_ON_GRPC_CANCELLED) &&
                    grpcStatus == Status.CANCELLED.getCode().value()) {
                    return shouldRetry;
                }
                if (policies.contains(RetryPolicyTypes.RETRY_ON_GRPC_DEADLINE_EXCEEDED) &&
                    grpcStatus == Status.DEADLINE_EXCEEDED.getCode().value()) {
                    return shouldRetry;
                }
                if (policies.contains(RetryPolicyTypes.RETRY_ON_GRPC_RESOURCE_EXHAUSTED) &&
                    grpcStatus == Status.RESOURCE_EXHAUSTED.getCode().value()) {
                    return shouldRetry;
                }
                if (policies.contains(RetryPolicyTypes.RETRY_ON_GRPC_UNAVAILABLE) &&
                    grpcStatus == Status.UNAVAILABLE.getCode().value()) {
                    return shouldRetry;
                }
                if (policies.contains(RetryPolicyTypes.RETRY_ON_GRPC_INTERNAL) &&
                    grpcStatus == Status.INTERNAL.getCode().value()) {
                    return shouldRetry;
                }
            }

            return RetryDecision.noRetry();
        }

        @Nullable
        private Backoff backoff(ResponseHeaders responseHeaders) {
            if (!retryPolicy.hasRateLimitedRetryBackOff()) {
                return null;
            }
            final RateLimitedRetryBackOff backOff = retryPolicy.getRateLimitedRetryBackOff();
            final long maxIntervalMillis = XdsCommonUtil.durationToMillis(backOff.getMaxInterval(), 300_000);
            for (ResetHeader resetHeader: backOff.getResetHeadersList()) {
                final String headerValue = responseHeaders.get(resetHeader.getName(), "");
                if (headerValue.isEmpty()) {
                    continue;
                }
                final Long seconds = XdsCommonUtil.simpleAtol(headerValue);
                if (seconds == null) {
                    continue;
                }
                final long intervalMillis;
                switch (resetHeader.getFormat()) {
                    case UNIX_TIMESTAMP:
                        final long nowSeconds = Instant.now().getEpochSecond();
                        intervalMillis =  TimeUnit.SECONDS.toMillis(seconds - nowSeconds);
                        break;
                    case SECONDS:
                    case UNRECOGNIZED:
                        intervalMillis = TimeUnit.SECONDS.toMillis(seconds);
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported format: " + resetHeader.getFormat());
                }
                if (intervalMillis > maxIntervalMillis) {
                    continue;
                }
                if (intervalMillis < 0) {
                    continue;
                }
                final long upperBoundMillis = (long) Math.floor(intervalMillis * 1.5);
                return Backoff.random(intervalMillis, upperBoundMillis);
            }
            return null;
        }

        @Override
        public CompletionStage<RetryDecision> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause) {
            if (cause != null) {
                return UnmodifiableFuture.completedFuture(handleReset(ctx, cause));
            }
            final RequestLog log = ctx.log().getIfAvailable(RequestLogProperty.RESPONSE_HEADERS);
            final ResponseHeaders responseHeaders;
            if (log == null) {
                responseHeaders = EMPTY_RESPONSE_HEADERS;
            } else {
                responseHeaders = log.responseHeaders();
            }
            backoff.updateDelegate(backoff(responseHeaders));
            return UnmodifiableFuture.completedFuture(handleResponseHeaders(responseHeaders));
        }
    }

    /**
     * A workaround to avoid {@link RetryingClient} resetting the attempt count
     * when the backoff reference changes.
     */
    private static final class DelegatingBackoff implements Backoff {

        private final Backoff defaultDelegate;
        @Nullable
        private Backoff delegate;

        private DelegatingBackoff(Backoff defaultDelegate) {
            this.defaultDelegate = defaultDelegate;
        }

        private void updateDelegate(@Nullable Backoff delegate) {
            this.delegate = delegate;
        }

        @Override
        public long nextDelayMillis(int numAttemptsSoFar) {
            final Backoff delegate = this.delegate;
            if (delegate == null) {
                return defaultDelegate.nextDelayMillis(numAttemptsSoFar);
            }
            return delegate.nextDelayMillis(numAttemptsSoFar);
        }
    }

    private enum RetryPolicyTypes {
        RETRY_ON_5XX("5xx"),
        RETRY_ON_GATEWAY_ERROR("gateway-error"),
        RETRY_ON_RETRIABLE_4XX("retriable-4xx"),
        RETRY_ON_RETRIABLE_STATUS_CODES("retriable-status-codes"),
        RETRY_ON_RETRIABLE_HEADERS("retriable-headers"),
        RETRY_ON_ENVOY_RATE_LIMITED("envoy-ratelimited"),
        // grpc
        RETRY_ON_GRPC_CANCELLED("cancelled"),
        RETRY_ON_GRPC_DEADLINE_EXCEEDED("deadline-exceeded"),
        RETRY_ON_GRPC_RESOURCE_EXHAUSTED("resource-exhausted"),
        RETRY_ON_GRPC_UNAVAILABLE("unavailable"),
        RETRY_ON_GRPC_INTERNAL("internal"),
        // reset
        RETRY_ON_HTTP3_POST_CONNECT_FAILURE("http3-post-connect-failure"), // http3 is not supported
        RETRY_ON_CONNECT_FAILURE("connect-failure"),
        RETRY_ON_REFUSED_STREAM("refused-stream"),
        RETRY_ON_RESET("reset"),
        RETRY_ON_RESET_BEFORE_REQUEST("reset-before-request");

        private final String policyName;

        private static final Map<String, RetryPolicyTypes> name2policy;

        static {
            final ImmutableMap.Builder<String, RetryPolicyTypes> name2policyBuilder = ImmutableMap.builder();
            for (RetryPolicyTypes retryPolicy : RetryPolicyTypes.values()) {
                name2policyBuilder.put(retryPolicy.policyName, retryPolicy);
            }
            name2policy =  name2policyBuilder.build();
        }

        RetryPolicyTypes(String policyName) {
            this.policyName = policyName;
        }

        @Nullable
        static RetryPolicyTypes fromPolicyName(String name) {
            return name2policy.get(name);
        }
    }
}
