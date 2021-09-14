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

package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A specification of a {@link Backoff} configuration represented by a string. The string syntax is
 * a series of comma-separated options(key-values pair) and each values is colon-separated.
 * The options are divided into two groups which are base options and decorators. Base options include
 * {@link ExponentialBackoff}, {@link FixedBackoff} and {@link RandomBackoff}. Only one of them can be
 * specified in the {@code specification}, otherwise {@link IllegalArgumentException} will occur.
 * When you create a {@link Backoff} using {@link #build()}, {@link JitterAddingBackoff} will be added
 * regardless whether you specify the {@code jitter} in the spec or not.
 *
 * @see Backoff#of(String) for the format of the specification, please refer to Backoff#of(String)
 */
final class BackoffSpec {
    private static final Splitter OPTION_SPLITTER = Splitter.on(',').trimResults();
    private static final Splitter KEY_VALUE_SPLITTER = Splitter.on('=').trimResults();
    private static final Splitter VALUE_SPLITTER = Splitter.on(':').trimResults();

    private static final List<String> ORDINALS = ImmutableList.of("first", "second", "third");

    private static final long DEFAULT_INITIAL_DELAY_MILLIS = 200;
    private static final long DEFAULT_MAX_DELAY_MILLIS = 10000;
    private static final double DEFAULT_MULTIPLIER = 2.0;
    private static final double DEFAULT_MAX_JITTER_RATE = 0.2;

    private final String specification;

    private enum BaseOption {
        exponential, // exponential backoff will be used to build when none of the base options are set
        fibonacci,
        fixed,
        random
    }

    @Nullable
    private BaseOption baseOption;

    private boolean jitterConfigured;
    private boolean maxAttemptsConfigured;

    @VisibleForTesting
    long initialDelayMillis = DEFAULT_INITIAL_DELAY_MILLIS;
    @VisibleForTesting
    long maxDelayMillis = DEFAULT_MAX_DELAY_MILLIS;
    @VisibleForTesting
    double multiplier = DEFAULT_MULTIPLIER;
    @VisibleForTesting
    double minJitterRate = -DEFAULT_MAX_JITTER_RATE;
    @VisibleForTesting
    double maxJitterRate = DEFAULT_MAX_JITTER_RATE;
    @VisibleForTesting
    int maxAttempts;
    @VisibleForTesting
    long fixedDelayMillis;
    @VisibleForTesting
    long randomMinDelayMillis;
    @VisibleForTesting
    long randomMaxDelayMillis;

    /**
     * Creates a {@link BackoffSpec} from a string.
     */
    static BackoffSpec parse(String specification) {
        requireNonNull(specification, "specification");
        final BackoffSpec spec = new BackoffSpec(specification);
        for (String option : OPTION_SPLITTER.split(specification)) {
            spec.parseOption(option);
        }
        return spec;
    }

    private BackoffSpec(String specification) {
        this.specification = specification;
    }

    private void parseOption(String option) {
        if (option.isEmpty()) {
            return;
        }

        final List<String> keyAndValues = KEY_VALUE_SPLITTER.splitToList(option);
        checkArgument(keyAndValues.size() == 2,
                      "option '%s' (expected 'key=value1:value2...')", option);

        configure(keyAndValues.get(0), keyAndValues.get(1));
    }

    private void configure(String key, String values) {
        switch (Ascii.toLowerCase(key)) {
            case "exponential":
                exponential(key, values);
                return;
            case "fibonacci":
                fibonacci(key, values);
                return;
            case "fixed":
                fixed(key, values);
                return;
            case "random":
                randomBackoff(key, values);
                return;
            case "jitter":
                jitter(key, values);
                return;
            case "maxattempts":
                maxAttempts(key, values);
                return;
            default:
                throw new IllegalArgumentException("Unknown key " + key);
        }
    }

    private void exponential(String key, String exponentialValues) {
        checkBaseBackoffConfigured();
        baseOption = BaseOption.exponential;

        final List<String> values = VALUE_SPLITTER.splitToList(exponentialValues);
        checkArgument(values.size() == 2 || values.size() == 3,
                      "the number of values for '%s' should be 2 or 3. input '%s'", key, exponentialValues);

        initialDelayMillis = parseLong(key, values.get(0), ORDINALS.get(0));
        checkNegative(key, initialDelayMillis, ORDINALS.get(0));

        maxDelayMillis = parseLong(key, values.get(1), ORDINALS.get(1));
        checkNegative(key, maxDelayMillis, ORDINALS.get(1));

        if (initialDelayMillis > maxDelayMillis) {
            final long temp = initialDelayMillis;
            initialDelayMillis = maxDelayMillis;
            maxDelayMillis = temp;
        }

        if (values.size() == 3) {
            multiplier = parseDouble(key, values.get(2), ORDINALS.get(2));
        }
    }

    private void checkBaseBackoffConfigured() {
        checkArgument(baseOption == null, "%s backoff is already set.", baseOption);
    }

    private static void checkNegative(String key, long value, String ordinal) {
        checkArgument(value >= 0, "%s parameter for %s must be a positive value. input: %s",
                      ordinal, key, value);
    }

    private void fibonacci(String key, String fibonacciValues) {
        checkBaseBackoffConfigured();
        baseOption = BaseOption.fibonacci;

        final List<String> values = VALUE_SPLITTER.splitToList(fibonacciValues);
        checkArgument(values.size() == 2,
                      "the number of values for '%s' should be 2. input '%s'", key, fibonacciValues);

        initialDelayMillis = parseLong(key, values.get(0), ORDINALS.get(0));
        checkNegative(key, initialDelayMillis, ORDINALS.get(0));
        maxDelayMillis = parseLong(key, values.get(1), ORDINALS.get(1));
        checkNegative(key, maxDelayMillis, ORDINALS.get(1));

        if (initialDelayMillis > maxDelayMillis) {
            final long temp = initialDelayMillis;
            initialDelayMillis = maxDelayMillis;
            maxDelayMillis = temp;
        }
    }

    private void fixed(String key, String value) {
        checkBaseBackoffConfigured();
        baseOption = BaseOption.fixed;
        fixedDelayMillis = parseLong(key, value, ORDINALS.get(0));
        checkNegative(key, fixedDelayMillis, ORDINALS.get(0));
    }

    private void randomBackoff(String key, String randomValues) {
        checkBaseBackoffConfigured();
        baseOption = BaseOption.random;

        final List<String> values = VALUE_SPLITTER.splitToList(randomValues);
        checkArgument(values.size() == 2,
                      "the number of values for '%s' should be 2. input '%s'", key, randomValues);

        randomMinDelayMillis = parseLong(key, values.get(0), ORDINALS.get(0));
        checkNegative(key, randomMinDelayMillis, ORDINALS.get(0));
        randomMaxDelayMillis = parseLong(key, values.get(1), ORDINALS.get(1));
        checkNegative(key, randomMaxDelayMillis, ORDINALS.get(1));

        if (randomMinDelayMillis > randomMaxDelayMillis) {
            final long temp = randomMinDelayMillis;
            randomMinDelayMillis = randomMaxDelayMillis;
            randomMaxDelayMillis = temp;
        }
    }

    private void jitter(String key, String jitterValues) {
        checkArgument(!jitterConfigured,
                      "jitter parameters are already set. minJitterRate: %s, maxJitterRate: %s",
                      minJitterRate, maxJitterRate);
        jitterConfigured = true;

        final List<String> values = VALUE_SPLITTER.splitToList(jitterValues);
        checkArgument(values.size() == 1 || values.size() == 2,
                      "the number of values for '%s' should be 1 or 2. input '%s'", key, jitterValues);

        if (values.size() == 1) {
            final double jitterRate = parseDouble(key, values.get(0), ORDINALS.get(0));
            checkDoubleBetween(key, jitterRate, 0.0, 1.0, ORDINALS.get(0));
            minJitterRate = jitterRate * -1;
            maxJitterRate = jitterRate;
        } else {
            minJitterRate = parseDouble(key, values.get(0), ORDINALS.get(0));
            checkDoubleBetween(key, minJitterRate, -1.0, 1.0, ORDINALS.get(0));
            maxJitterRate = parseDouble(key, values.get(1), ORDINALS.get(1));
            checkDoubleBetween(key, maxJitterRate, -1.0, 1.0, ORDINALS.get(1));
            if (minJitterRate > maxJitterRate) {
                final double temp = minJitterRate;
                minJitterRate = maxJitterRate;
                maxJitterRate = temp;
            }
        }
    }

    private static void checkDoubleBetween(String key, double value, double min, double max, String ordinal) {
        checkArgument(min <= value && value <= max,
                      "%s parameter for %s must be >= %s and <= %s. input: %s",
                      ordinal, key, min, max, value);
    }

    private void maxAttempts(String key, String value) {
        checkArgument(!maxAttemptsConfigured, "maxAttempts parameters is already set. maxAttempts: %s",
                      maxAttempts);
        maxAttemptsConfigured = true;
        maxAttempts = parseInt(key, value);
        checkNegative(key, maxAttempts, ORDINALS.get(0));
    }

    private static long parseLong(String key, String value, String ordinal) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format(
                    "%s parameter for %s was set to %s, must be a long", ordinal, key, value), e);
        }
    }

    private static double parseDouble(String key, String value, String ordinal) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format(
                    "%s parameter for %s was set to %s, must be a double", ordinal, key, value), e);
        }
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    String.format("%s was set to %s, must be an integer", key, value), e);
        }
    }

    /**
     * Returns a newly-created {@link Backoff} based on the properties of this spec.
     */
    Backoff build() {
        Backoff backoff;
        if (baseOption == BaseOption.fixed) {
            backoff = Backoff.fixed(fixedDelayMillis);
        } else if (baseOption == BaseOption.random) {
            backoff = Backoff.random(randomMinDelayMillis, randomMaxDelayMillis);
        } else if (baseOption == BaseOption.fibonacci) {
            backoff = Backoff.fibonacci(initialDelayMillis, maxDelayMillis);
        } else {
            backoff = Backoff.exponential(initialDelayMillis, maxDelayMillis, multiplier);
        }

        backoff = backoff.withJitter(minJitterRate, maxJitterRate);
        if (maxAttemptsConfigured) {
            backoff = backoff.withMaxAttempts(maxAttempts);
        }

        return backoff;
    }

    @Override
    public String toString() {
        final ToStringHelper stringHelper = MoreObjects.toStringHelper(this)
                                                       .add("specification", specification);
        if (baseOption == BaseOption.fixed) {
            stringHelper.add("fixedDelayMillis", fixedDelayMillis);
        } else if (baseOption == BaseOption.random) {
            stringHelper.add("randomMinDelayMillis", randomMinDelayMillis)
                        .add("randomMaxDelayMillis", randomMaxDelayMillis);
        } else {
            stringHelper.add("initialDelayMillis", initialDelayMillis).add("maxDelayMillis", maxDelayMillis)
                        .add("multiplier", multiplier);
        }

        stringHelper.add("minJitterRate", minJitterRate).add("maxJitterRate", maxJitterRate);
        if (maxAttemptsConfigured) {
            stringHelper.add("maxAttempts", maxAttempts);
        }

        return stringHelper.toString();
    }
}
