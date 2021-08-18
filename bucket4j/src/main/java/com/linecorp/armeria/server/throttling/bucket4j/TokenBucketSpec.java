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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.common.base.Splitter;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A specification of a {@link TokenBucket} configuration represented by a string. The string syntax is
 * a series of comma-separated {@link BandwidthLimit} configurations and each values is semicolon-separated,
 * as per <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/">RateLimit Header
 * Fields for HTTP</a>.
 *
 * @see #parseTokenBucket(String) for detailed format of the specification.
 */
final class TokenBucketSpec {

    private static final char OPTIONS_SEPARATOR = ';';
    private static final char KEY_VALUE_SEPARATOR = '=';
    private static final String PERIOD = "window";
    private static final String OVERDRAFT = "burst";
    private static final String INITIAL = "initial";
    private static final Splitter.MapSplitter OPTIONS_SPLITTER =
            Splitter.on(OPTIONS_SEPARATOR).trimResults().withKeyValueSeparator(KEY_VALUE_SEPARATOR);
    private static final Splitter LIMITS_SPLITTER = Splitter.on(',').trimResults();

    private TokenBucketSpec() {}

    /**
     * Creates a new {@link BandwidthLimit} that computes {@code limit}, {@code overdraftLimit},
     * {@code initialSize} and {@code period} from a semicolon-separated {@code specification} string
     * that conforms to the following format:
     * <pre>{@code
     * <limit>;window=<period(in seconds)>[;burst=<overdraftLimit>][;initial=<initialSize>]
     * }</pre>
     * All {@code specification} string elements must come in the defined order.
     * For example:
     * <ul>
     *   <li>{@code 100;window=60;burst=1000} ({@code limit}=100, {@code overdraftLimit}=1000,
     *       {@code initialSize} and {@code period}=60seconds)</li>
     *   <li>{@code 100;window=60;burst=1000;initial=20} ({@code limit}=100, {@code overdraftLimit}=1000,
     *       {@code initialSize}=20 and {@code period}=60seconds)</li>
     *   <li>{@code 5000;window=1} ({@code limit}=5000 and {@code period}=1second)</li>
     * </ul>
     *
     * @param specification the specification used to create a {@link BandwidthLimit}
     */
    static BandwidthLimit parseBandwidthLimit(String specification) {
        requireNonNull(specification, "specification");
        if (specification.isEmpty()) {
            throw new IllegalArgumentException("Empty bandwidth limit specification");
        }
        final int limitSep = specification.indexOf(OPTIONS_SEPARATOR);
        final long limit;
        final Map<String, String> options;
        if (limitSep > 0) {
            limit = Long.parseLong(specification.substring(0, limitSep));
            options = (limitSep < specification.length() - 1) ?
                      OPTIONS_SPLITTER.split(specification.substring(limitSep + 1)) : Collections.emptyMap();
        } else if (limitSep < 0) {
            limit = Long.parseLong(specification);
            options = Collections.emptyMap();
        } else { // if (limitSep == 0)
            throw new IllegalArgumentException("Invalid format of \"" +
                                               specification + "\" - limit not found");
        }
        if (!options.containsKey(PERIOD)) {
            throw new IllegalArgumentException("Invalid format of \"" +
                                               specification + "\" - period not found");
        }
        final Duration period = Duration.ofSeconds(Long.parseLong(options.get(PERIOD)));
        if (options.containsKey(OVERDRAFT)) {
            final long overdraftLimit = Long.parseLong(options.get(OVERDRAFT));
            if (options.containsKey(INITIAL)) {
                final long initialSize = Long.parseLong(options.get(INITIAL));
                return BandwidthLimit.of(limit, overdraftLimit, initialSize, period);
            } else {
                return BandwidthLimit.of(limit, overdraftLimit, period);
            }
        } else {
            return BandwidthLimit.of(limit, period);
        }
    }

    /**
     * Creates a new {@link TokenBucket} from a comma-separated {@code specification} string
     * that conforms to the following format:
     * <pre>{@code
     * <bandwidth limit 1>[, <bandwidth limit 2>[, etc.]]
     * }</pre>
     * The order of elements inside {@code specification} is not defined.
     * For example:
     * <ul>
     *   <li>{@code 100;window=60;burst=1000, 50000;window=3600}</li>
     * </ul>
     *
     * @param specification the specification used to create a {@link BandwidthLimit}.
     *                      Empty {@link String} permitted to specify empty {@link TokenBucket}.
     */
    static TokenBucket parseTokenBucket(String specification) {
        requireNonNull(specification, "specification");
        final TokenBucketBuilder builder = TokenBucket.builder();
        if (specification.isEmpty()) {
            // empty specification allowed here
            return builder.limits().build();
        }
        final List<BandwidthLimit> limits = new ArrayList<>(2);
        for (String limitSpec: LIMITS_SPLITTER.split(specification)) {
            limits.add(parseBandwidthLimit(limitSpec));
        }
        return builder.limits(limits).build();
    }

    /**
     * Returns a string representation of the {@link BandwidthLimit} in the following format:
     * <pre>{@code
     * <limit>;window=<period(in seconds)>[;burst=<overdraftLimit>][;policy="token bucket"]
     * }</pre>
     * For example: "100;window=60;burst=1000".
     * <br>
     * This method used to compose Quota Policy response header to inform the client about rate
     * limiting policy as per
     * <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/">RateLimit Header Fields
     * for HTTP</a>.
     *
     * @return A {@link String} representation of the {@link BandwidthLimit}.
     */
    @Nullable
    static String toString(@Nullable BandwidthLimit bandwidthLimit) {
        if (bandwidthLimit == null) {
            return null;
        }
        final long limit = bandwidthLimit.limit();
        final StringBuilder sb = new StringBuilder().append(limit);
        sb.append(OPTIONS_SEPARATOR).append(PERIOD).append(KEY_VALUE_SEPARATOR)
          .append(bandwidthLimit.period().getSeconds());
        final long overdraftLimit = bandwidthLimit.overdraftLimit();
        if (overdraftLimit > limit) {
            sb.append(OPTIONS_SEPARATOR).append(OVERDRAFT).append(KEY_VALUE_SEPARATOR).append(overdraftLimit);
        }
        // We don't want to include INITIAL size with the Quota Policy response, since it's only relevant
        // during the warm-up.
        return sb.toString();
    }

    /**
     * Returns a string representation of the {@link TokenBucket} in the following format:
     * <pre>{@code
     * <first limit>;window=<first period(in seconds)>[;burst=<first overdraftLimit>],<SPACE>
     * <second limit>;window=<second period(in seconds)>[;burst=<second overdraftLimit>],<SPACE>etc.
     * }</pre>
     * For example: "100;window=60;burst=1000, 5000;window=3600".
     * <br>
     * This method used to compose Quota Policy response header to inform the client about rate
     * limiting policy as per
     * <a href="https://datatracker.ietf.org/doc/draft-ietf-httpapi-ratelimit-headers/">RateLimit Header Fields
     * for HTTP</a>.
     *
     * @return A {@link String} representation of the {@link TokenBucket}.
     */
    @Nullable
    static String toString(@Nullable TokenBucket tokenBucket) {
        if (tokenBucket == null) {
            return null;
        }
        final BandwidthLimit[] limits = tokenBucket.limits();
        return Arrays.stream(limits).map(TokenBucketSpec::toString).collect(Collectors.joining(", "));
    }
}
