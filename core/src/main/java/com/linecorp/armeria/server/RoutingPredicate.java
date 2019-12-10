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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.server.RoutingPredicate.ParsedComparingPredicate.parse;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpParameters;

/**
 * A {@link Predicate} to evaluate whether a request can be accepted by a service method.
 * Currently predicates for {@link HttpHeaders} and HTTP parameters are supported.
 *
 * @param <T> the type of the object to be tested
 */
final class RoutingPredicate<T> {
    private static final Logger logger = LoggerFactory.getLogger(RoutingPredicate.class);

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

    static List<RoutingPredicate<HttpHeaders>> copyOfHeaderPredicates(Iterable<String> predicates) {
        return StreamSupport.stream(predicates.spliterator(), false)
                            .map(RoutingPredicate::ofHeaders).collect(toImmutableList());
    }

    static List<RoutingPredicate<HttpParameters>> copyOfParamPredicates(Iterable<String> predicates) {
        return StreamSupport.stream(predicates.spliterator(), false)
                            .map(RoutingPredicate::ofParams).collect(toImmutableList());
    }

    static RoutingPredicate<HttpHeaders> ofHeaders(CharSequence headerName, Predicate<String> valuePredicate) {
        return new RoutingPredicate<>(headerName,
                                      headers -> headers.getAll(headerName).stream().anyMatch(valuePredicate));
    }

    @VisibleForTesting
    static RoutingPredicate<HttpHeaders> ofHeaders(String headersPredicate) {
        requireNonNull(headersPredicate, "headersPredicate");
        final Matcher m = CONTAIN_PATTERN.matcher(headersPredicate);
        if (m.matches()) {
            final CharSequence headerName = m.group(2);
            final Predicate<HttpHeaders> predicate = headers -> headers.contains(headerName);
            return new RoutingPredicate<>(headersPredicate,
                                          "!".equals(m.group(1)) ? predicate.negate() : predicate);
        }

        final ParsedComparingPredicate parsed = parse(headersPredicate);
        final Predicate<HttpHeaders> predicate;
        if ("=".equals(parsed.comparator)) {
            predicate = headers -> headers.getAll(parsed.name).stream()
                                          .anyMatch(parsed.value::equals);
        } else {
            assert "!=".equals(parsed.comparator);
            predicate = headers -> headers.getAll(parsed.name).stream()
                                          .noneMatch(parsed.value::equals);
        }
        return new RoutingPredicate<>(headersPredicate, predicate);
    }

    static RoutingPredicate<HttpParameters> ofParams(String paramName, Predicate<String> valuePredicate) {
        return new RoutingPredicate<>(paramName,
                                      params -> params.getAll(paramName).stream().anyMatch(valuePredicate));
    }

    @VisibleForTesting
    static RoutingPredicate<HttpParameters> ofParams(String paramsPredicate) {
        requireNonNull(paramsPredicate, "paramsPredicate");
        final Matcher m = CONTAIN_PATTERN.matcher(paramsPredicate);
        if (m.matches()) {
            final String paramName = m.group(2);
            final Predicate<HttpParameters> predicate = params -> params.contains(paramName);
            return new RoutingPredicate<>(paramsPredicate,
                                          "!".equals(m.group(1)) ? predicate.negate() : predicate);
        }

        final ParsedComparingPredicate parsed = parse(paramsPredicate);
        final Predicate<HttpParameters> predicate;
        if ("=".equals(parsed.comparator)) {
            predicate = params -> params.getAll(parsed.name).stream()
                                        .anyMatch(parsed.value::equals);
        } else {
            assert "!=".equals(parsed.comparator);
            predicate = params -> params.getAll(parsed.name).stream()
                                        .noneMatch(parsed.value::equals);
        }
        return new RoutingPredicate<>(paramsPredicate, predicate);
    }

    private final CharSequence name;
    private final Predicate<T> delegate;

    RoutingPredicate(CharSequence name, Predicate<T> delegate) {
        this.name = requireNonNull(name, "name");
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the name of this predicate. This may be the predicate that a user specified.
     */
    CharSequence name() {
        return name;
    }

    /**
     * Tests the specified {@code t} object.
     *
     * @see DefaultRoute where this predicate is evalued
     */
    public boolean test(T t) {
        try {
            return delegate.test(t);
        } catch (Throwable cause) {
            // Do not write the following log message every time because an abnormal request may be
            // from an abusing user or a hacker and logging it every time may affect system performance.
            if (Flags.verboseExceptionSampler().isSampled(cause.getClass())) {
                logger.warn("Failed to evaluate the value of header or param '{}'. " +
                            "You MUST catch and handle this exception properly: " +
                            "input={}", name, t, cause);
            }
            return false;
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("delegate", delegate)
                          .toString();
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
