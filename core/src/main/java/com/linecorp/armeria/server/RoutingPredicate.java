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
import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

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
     * A pattern used to parse a given predicate. The predicate can be one of the following forms:
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
     *
     * <p>This pattern parses a predicate as following groups:
     * <ul>
     *     <li>Group 1: whether the inversion mark exists</li>
     *     <li>Group 2: the name part</li>
     *     <li>Group 3: the equal or not-equal comparator</li>
     *     <li>Group 4: the value part</li>
     * </ul>
     */
    private static final Pattern CONDITION_PATTERN =
            Pattern.compile("\\s*([!]?)([^\\s!=]+)\\s*(?:([!]?=)(.*))?$");

    /**
     * Returns a new {@link RoutingPredicate} for {@link HttpHeaders}.
     */
    static RoutingPredicate<HttpHeaders> ofHeaders(CharSequence headersPredicate) {
        final ParsedPredicate parsed =
                parsePredicate(requireNonNull(headersPredicate, "headersPredicate"));
        final Predicate<HttpHeaders> predicate;
        if (parsed.value != null) {
            predicate = headers -> parsed.value.equals(headers.get(parsed.name));
        } else {
            predicate = headers -> headers.contains(parsed.name);
        }
        return new RoutingPredicate<>(headersPredicate, parsed.isInverted ? predicate.negate() : predicate);
    }

    /**
     * Returns a new {@link RoutingPredicate} for HTTP parameters.
     */
    static RoutingPredicate<HttpParameters> ofParams(CharSequence paramsPredicate) {
        final ParsedPredicate parsed =
                parsePredicate(requireNonNull(paramsPredicate, "paramsPredicate"));
        final Predicate<HttpParameters> predicate;
        if (parsed.value != null) {
            predicate = params -> params.getAll(parsed.name)
                                        .stream().anyMatch(parsed.value::equals);
        } else {
            predicate = params -> params.contains(parsed.name);
        }
        return new RoutingPredicate<>(paramsPredicate, parsed.isInverted ? predicate.negate() : predicate);
    }

    @VisibleForTesting
    static ParsedPredicate parsePredicate(CharSequence predicate) {
        final Matcher m = CONDITION_PATTERN.matcher(predicate);
        checkPredicate(m::matches, predicate);
        assert m.groupCount() == 4;

        final String name = m.group(2);
        checkPredicate(() -> !Strings.isNullOrEmpty(name), predicate);

        final String comparator = m.group(3);
        if (comparator == null) {
            // If no comparator, the value must not exist.
            assert Strings.isNullOrEmpty(m.group(4));
            return new ParsedPredicate("!".equals(m.group(1)), name, null);
        }

        // If comparator exists, the leading "!" must not exist.
        checkPredicate(() -> Strings.isNullOrEmpty(m.group(1)), predicate);

        // If comparator exists, the value must exist.
        final String value = m.group(4);
        checkPredicate(() -> !Strings.isNullOrEmpty(value), predicate);

        return new ParsedPredicate("!=".equals(comparator), name, value);
    }

    private static void checkPredicate(BooleanSupplier test, CharSequence predicate) {
        checkArgument(test.getAsBoolean(),
                      "Invalid predicate: %s (expected: %s)",
                      predicate, CONDITION_PATTERN.pattern());
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
    static final class ParsedPredicate {
        final boolean isInverted;
        final String name;
        @Nullable
        final String value;

        private ParsedPredicate(boolean isInverted, String name, @Nullable String value) {
            this.isInverted = isInverted;
            this.name = name;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (!(o instanceof ParsedPredicate)) {
                return false;
            }

            final ParsedPredicate that = (ParsedPredicate) o;
            return isInverted == that.isInverted &&
                   name.equals(that.name) &&
                   Objects.equals(value, that.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(isInverted, name, value);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).omitNullValues()
                              .add("isInverted", isInverted)
                              .add("name", name)
                              .add("value", value)
                              .toString();
        }
    }
}
