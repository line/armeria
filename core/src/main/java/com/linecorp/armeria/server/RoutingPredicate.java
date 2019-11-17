/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.server.RoutingPredicate.ParsedComparingPredicate.parse;
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpParameters;

/**
 * A {@link Predicate} to evaluate whether a request can be accepted by a service method.
 * Currently predicates for {@link HttpHeaders} and HTTP parameters are supported.
 *
 * @param <T> the type of the object to be tested
 */
final class RoutingPredicate<T> implements Predicate<T> {

    /**
     * Patterns used to parse a given predicate. The predicate can be one of the following forms:
     * <ul>
     *     <li>{@code some-name=some-value} which means that the request must have a
     *     {@code some-name=some-value} header or parameter</li>
     *     <li>{@code some-name!=some-value} which means that the request must not have a
     *     {@code some-name=some-value} header or parameter</li>
     *     <li>{@code some-name} which means that the request must contain a {@code some-name} header or
     *     parameter</li>
     *     <li>{@code !some-name} which means that the request must not contain a {@code some-name} header or
     *     parameter</li>
     * </ul>
     */
    @VisibleForTesting
    static final Pattern CONTAIN_PATTERN = Pattern.compile("^\\s*([!]?)([^\\s=><!]+)\\s*$");
    private static final Pattern COMPARE_PATTERN = Pattern.compile("^\\s*([^\\s!><=]+)\\s*([><!]?=|>|<)(.*)$");

    /**
     * Returns a new {@link RoutingPredicate} for {@link HttpHeaders}.
     */
    static RoutingPredicate<HttpHeaders> ofHeaders(CharSequence headersPredicate) {
        requireNonNull(headersPredicate, "headersPredicate");
        final Matcher m = CONTAIN_PATTERN.matcher(headersPredicate);
        if (m.matches()) {
            final Predicate<HttpHeaders> predicate = headers -> headers.contains(m.group(2));
            return new RoutingPredicate<>(headersPredicate,
                                          "!".equals(m.group(1)) ? predicate.negate() : predicate);
        }

        final ParsedComparingPredicate parsed = parse(headersPredicate);
        final Predicate<HttpHeaders> predicate =
                headers -> headers.getAll(parsed.name).stream()
                                  .anyMatch(valueMatcher(parsed.comparator, parsed.value));
        return new RoutingPredicate<>(headersPredicate, predicate);
    }

    /**
     * Returns a new {@link RoutingPredicate} for {@link HttpParameters}.
     */
    static RoutingPredicate<HttpParameters> ofParams(String paramsPredicate) {
        requireNonNull(paramsPredicate, "paramsPredicate");
        final Matcher m = CONTAIN_PATTERN.matcher(paramsPredicate);
        if (m.matches()) {
            final Predicate<HttpParameters> predicate = params -> params.contains(m.group(2));
            return new RoutingPredicate<>(paramsPredicate,
                                          "!".equals(m.group(1)) ? predicate.negate() : predicate);
        }

        final ParsedComparingPredicate parsed = parse(paramsPredicate);
        final Predicate<HttpParameters> predicate =
                params -> params.getAll(parsed.name).stream()
                                .anyMatch(valueMatcher(parsed.comparator, parsed.value));
        return new RoutingPredicate<>(paramsPredicate, predicate);
    }

    private static Predicate<String> valueMatcher(String comparator, String configuredValue) {
        try {
            final long longConfiguredValue = Long.parseLong(configuredValue);
            switch (comparator) {
                case "=":
                    return longValueMatcher(value -> value == longConfiguredValue);
                case "!=":
                    return longValueMatcher(value -> value != longConfiguredValue);
                case ">":
                    return longValueMatcher(value -> value > longConfiguredValue);
                case ">=":
                    return longValueMatcher(value -> value >= longConfiguredValue);
                case "<":
                    return longValueMatcher(value -> value < longConfiguredValue);
                case "<=":
                    return longValueMatcher(value -> value <= longConfiguredValue);
            }
        } catch (NumberFormatException nfe) {
            switch (comparator) {
                case "=":
                    return configuredValue::equals;
                case "!=":
                    return value -> !value.equals(configuredValue);
                case ">":
                    return value -> value.compareTo(configuredValue) > 0;
                case ">=":
                    return value -> value.compareTo(configuredValue) >= 0;
                case "<":
                    return value -> value.compareTo(configuredValue) < 0;
                case "<=":
                    return value -> value.compareTo(configuredValue) <= 0;
            }
        }
        throw new IllegalStateException("Pattern has changed unexpectedly: " + COMPARE_PATTERN.pattern());
    }

    private static Predicate<String> longValueMatcher(Predicate<Long> comparator) {
        return value -> {
            try {
                final long longValue = Long.parseLong(value);
                return comparator.test(longValue);
            } catch (Exception e) {
                return false;
            }
        };
    }

    private final CharSequence id;
    private final Predicate<T> delegate;

    RoutingPredicate(CharSequence id, Predicate<T> delegate) {
        this.id = requireNonNull(id, "id");
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the ID of this predicate. This may be the predicate that a user specified.
     */
    CharSequence id() {
        return id;
    }

    /**
     * Tests the specified {@code t} object.
     *
     * @see DefaultRoute where this predicate is evalued
     */
    @Override
    public boolean test(T t) {
        return delegate.test(t);
    }

    @VisibleForTesting
    static final class ParsedComparingPredicate {

        @VisibleForTesting
        static ParsedComparingPredicate parse(CharSequence predicate) {
            final Matcher compareMatcher = COMPARE_PATTERN.matcher(predicate);
            checkArgument(compareMatcher.matches(),
                          "Invalid predicate: %s (expected: '%s' or '%s')",
                          predicate, CONTAIN_PATTERN.pattern(), COMPARE_PATTERN.pattern());
            assert compareMatcher.groupCount() == 3;

            final String name = compareMatcher.group(1);
            final String comparator = compareMatcher.group(2);
            final String value = compareMatcher.group(3);
            assert name != null && comparator != null && value != null;

            return new ParsedComparingPredicate(name, comparator, value);
        }

        final String name;
        final String comparator;
        final String value;

        private ParsedComparingPredicate(String name, String comparator, String value) {
            this.name = name;
            this.comparator = comparator;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof ParsedComparingPredicate)) {
                return false;
            }

            final ParsedComparingPredicate that = (ParsedComparingPredicate) o;
            return name.equals(that.name) &&
                   Objects.equals(comparator, that.comparator) &&
                   Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, comparator, value);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).omitNullValues()
                              .add("name", name)
                              .add("comparator", comparator)
                              .add("value", value)
                              .toString();
        }
    }
}
