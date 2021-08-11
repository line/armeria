/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.throttling.bucket4j;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.throttling.ThrottlingHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.throttling.ThrottlingStrategy;

import io.github.bucket4j.AsyncBucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.ConfigurationBuilder;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.github.bucket4j.local.LocalBucketBuilder;

/**
 * A {@link ThrottlingStrategy} that provides a throttling strategy based on Token-Bucket algorithm.
 * The throttling works by examining the number of requests from the beginning, and
 * throttling if the request rate exceed the configured bucket limits.
 */
@UnstableApi
public final class TokenBucketThrottlingStrategy<T extends Request> extends ThrottlingStrategy<T> {

    /**
     * Returns a newly created {@link TokenBucketThrottlingStrategyBuilder}.
     */
    public static <T extends Request> TokenBucketThrottlingStrategyBuilder<T> builder(TokenBucket tokenBucket) {
        return new TokenBucketThrottlingStrategyBuilder<>(tokenBucket);
    }

    private final AsyncBucket asyncBucket;
    private final long minimumBackoffSeconds;
    @Nullable
    private final ThrottlingHeaders headersScheme;
    @Nullable
    private String quota;
    private final boolean sendQuota;

    /**
     * Creates a new named strategy with specified {@link TokenBucket} configuration,
     * with minimum backoff period and with specific throttling headers scheme.
     *
     * @param tokenBucket {@link TokenBucket} configuration.
     * @param minimumBackoff optional {@link Duration} that defines a minimum backoff period
     *                       for throttled requests. By default, it will be set to 0 seconds.
     * @param headersScheme optional {@link ThrottlingHeaders} to define specific RateLimit Header Scheme
     *                      for HTTP. By default, no throttling headers will be used. The strategy will only use
     *                      standard HTTP Retry-After header.
     * @param sendQuota indicates whether to use quota header for the scheme.
     * @param name optional name of the strategy. By default, it will be assigned with a predefined name.
     */
    TokenBucketThrottlingStrategy(TokenBucket tokenBucket,
                                  @Nullable Duration minimumBackoff,
                                  @Nullable ThrottlingHeaders headersScheme,
                                  boolean sendQuota,
                                  @Nullable String name) {
        super(name);
        // construct the bucket builder
        final LocalBucketBuilder builder = Bucket4j.builder().withNanosecondPrecision();
        for (BandwidthLimit limit : tokenBucket.limits()) {
            builder.addLimit(limit.bandwidth());
        }
        // build the bucket
        asyncBucket = builder.build().asAsync();
        minimumBackoffSeconds = (minimumBackoff == null) ? 0L : minimumBackoff.getSeconds();
        this.headersScheme = headersScheme;
        this.sendQuota = sendQuota;
        quota = sendQuota ? tokenBucket.toSpecString() : null;
    }

    /**
     * Resets the Token-Bucket configuration at runtime by providing a new set of limits.
     * Empty set of limits will remove previously set limits. Reconfiguration will take place asynchronously.
     * @param tokenBucket Token-Bucket configuration
     * @return A {@link CompletableFuture} to handle asynchronous result
     */
    public CompletableFuture<Void> reconfigure(TokenBucket tokenBucket) {
        // construct the configuration builder
        final ConfigurationBuilder builder = Bucket4j.configurationBuilder();
        for (BandwidthLimit limit : tokenBucket.limits()) {
            builder.addLimit(limit.bandwidth());
        }
        // reconfigure the bucket
        return asyncBucket.replaceConfiguration(builder.build(),
                                                TokensInheritanceStrategy.PROPORTIONALLY)
                          .thenRun(() -> quota = sendQuota ? tokenBucket.toSpecString() : null);
    }

    /**
     * Registers a request with the bucket.
     */
    @Override
    public CompletionStage<Boolean> accept(final ServiceRequestContext ctx, final T request) {
        return asyncBucket.tryConsumeAndReturnRemaining(1L).thenApply(probe -> {
            final boolean accepted = probe.isConsumed();
            final long remainingTokens = probe.getRemainingTokens();
            final long remainingSeconds =
                    TimeUnit.SECONDS.convert(probe.getNanosToWaitForRefill(), TimeUnit.NANOSECONDS);
            // calculate maximum between pre-configured minimum backoff and remaining seconds
            final long retryAfter = Math.max(minimumBackoffSeconds, remainingSeconds);
            if (!accepted) {
                // always send Retry-After header for rejected requests
                ctx.addAdditionalResponseHeader(HttpHeaderNames.RETRY_AFTER, retryAfter);
            }
            if (headersScheme != null) {
                // when headers scheme defined,
                // add those to the response (either for accepted or rejected requests)
                ctx.addAdditionalResponseHeader(headersScheme.remainingHeader(), remainingTokens);
                ctx.addAdditionalResponseHeader(headersScheme.resetHeader(),
                                                accepted ? remainingSeconds : retryAfter);
                if (sendQuota && quota != null) {
                    ctx.addAdditionalResponseHeader(headersScheme.limitHeader(), quota);
                }
            }
            return accepted;
        });
    }
}
