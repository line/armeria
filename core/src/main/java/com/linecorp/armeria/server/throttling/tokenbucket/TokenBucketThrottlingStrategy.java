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

package com.linecorp.armeria.server.throttling.tokenbucket;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.throttling.RetryThrottlingStrategy;
import com.linecorp.armeria.server.throttling.ThrottlingStrategy;

import io.github.bucket4j.AsyncBucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.ConfigurationBuilder;
import io.github.bucket4j.local.LocalBucketBuilder;

/**
 * A {@link ThrottlingStrategy} that provides a throttling strategy based on Token-Bucket algorithm.
 * The throttling works by examining the number of requests from the beginning, and
 * throttling if the request rate exceed the configured bucket limits.
 */
public class TokenBucketThrottlingStrategy<T extends Request> extends RetryThrottlingStrategy<T> {
    private final AsyncBucket bucket;
    private TokenBucketConfig config;
    private String retryAfterSeconds;

    /**
     * Creates a new strategy with specified configuration and name.
     * @param config Token-Bucket configuration
     * @param name the name of the strategy
     */
    public TokenBucketThrottlingStrategy(@Nonnull TokenBucketConfig config, @Nullable String name) {
        super(name);
        setConfig(config);
        // construct the bucket builder
        final LocalBucketBuilder builder = Bucket4j.builder().withNanosecondPrecision();
        for (BandwidthLimit limit : config.limits()) {
            builder.addLimit(limit.bandwidth());
        }
        // build the bucket
        bucket = builder.build().asAsync();
    }

    /**
     * Creates a new strategy with specified configuration and default name.
     * @param config Token-Bucket configuration
     */
    public TokenBucketThrottlingStrategy(@Nonnull TokenBucketConfig config) {
        this(config, "TokenBucketThrottlingStrategy");
    }

    private void setConfig(@Nonnull TokenBucketConfig config) {
        this.config = Objects.requireNonNull(config);
        retryAfterSeconds = String.valueOf(config.retryAfterTimeout().getSeconds());
    }

    /**
     * Returns {@link TokenBucketConfig}.
     */
    @Nonnull
    public TokenBucketConfig getConfig() {
        return config;
    }

    /**
     * Provides a performance shortcut to {@link TokenBucketConfig#retryAfterTimeout()}.
     */
    @Override
    protected String retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * Allows resetting the Token-Bucket configuration at runtime by providing a new set of limits.
     * Empty set of limits will remove previously set limits.
     * @param config {@link TokenBucketConfig}
     */
    public CompletableFuture<Void> replaceConfiguration(@Nonnull TokenBucketConfig config) {
        setConfig(config);

        // construct the configuration builder
        final ConfigurationBuilder builder = Bucket4j.configurationBuilder();
        for (BandwidthLimit limit : config.limits()) {
            builder.addLimit(limit.bandwidth());
        }
        // reconfigure the bucket
        return bucket.replaceConfiguration(builder.build());
    }

    /**
     * Registers a request with the bucket.
     */
    @Override
    public CompletionStage<Boolean> accept(ServiceRequestContext ctx, T request) {
        return bucket.tryConsume(1L);
    }
}
