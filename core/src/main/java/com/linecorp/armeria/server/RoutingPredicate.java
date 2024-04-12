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
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AsciiString;

/**
 * A {@link Predicate} to evaluate whether a request can be accepted by a service method.
 * Currently, predicates for {@link HttpHeaders} and {@link QueryParams} are supported.
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
     *     <li>{@code some-name=some-value || some-other-value} which means that the request must have
     *     a {@code some-name=some-value} or a {@code some-other-value} header or parameter</li>
     * </ul>
     */
    private static final Pattern CONTAIN_PATTERN = Pattern.compile("^\\s*([!]?)([^\\s=><!]+)\\s*$");
    private static final Pattern COMPARE_PATTERN = Pattern.compile("^\\s*([^\\s!><=]+)\\s*([><!]?=|>|<)(.*)$");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s");
    private static final Pattern OR_PATTERN = Pattern.compile("\\s*\\|\\|\\s*");

    static List<RoutingPredicate<HttpHeaders>> copyOfHeaderPredicates(Iterable<String> predicates) {
        return Streams.stream(predicates)
                      .map(RoutingPredicate::ofHeaders).collect(toImmutableList());
    }

    static List<RoutingPredicate<QueryParams>> copyOfParamPredicates(Iterable<String> predicates) {
        return Streams.stream(predicates)
                      .map(RoutingPredicate::ofParams).collect(toImmutableList());
    }

    static RoutingPredicate<HttpHeaders> ofHeaders(CharSequence headerName,
                                                   Predicate<? super String> valuePredicate) {
        final AsciiString name = HttpHeaderNames.of(headerName);
        return new RoutingPredicate<>(headerName, headers ->
                headers.getAll(name).stream().anyMatch(valuePredicate), true);
    }

    @VisibleForTesting
    static RoutingPredicate<HttpHeaders> ofHeaders(String headersPredicate) {
        requireNonNull(headersPredicate, "headersPredicate");
        return of(headersPredicate, HttpHeaderNames::of, name -> headers -> headers.contains(name),
                  (name, value) -> headers -> headers.getAll(name).stream().anyMatch(value::equals));
    }

    static RoutingPredicate<QueryParams> ofParams(String paramName,
                                                  Predicate<? super String> valuePredicate) {
        return new RoutingPredicate<>(paramName, params ->
                params.getAll(paramName).stream().anyMatch(valuePredicate), true);
    }

    @VisibleForTesting
    static RoutingPredicate<QueryParams> ofParams(String paramsPredicate) {
        requireNonNull(paramsPredicate, "paramsPredicate");
        return of(paramsPredicate, Function.identity(), name -> params -> params.contains(name),
                  (name, value) -> params -> params.getAll(name).stream().anyMatch(value::equals));
    }

    @VisibleForTesting
    static <T, U> RoutingPredicate<T> of(String predicateExpr,
                                         Function<String, U> nameConverter,
                                         Function<U, Predicate<T>> containsPredicate,
                                         BiFunction<U, String, Predicate<T>> equalsPredicate) {
        final RoutingPredicate<T> routingPredicate = parseRoutingPredicate(predicateExpr,
                                                                           nameConverter,
                                                                           containsPredicate,
                                                                           equalsPredicate);

        checkArgument(routingPredicate != null,
                      "Invalid predicate: %s (expected: " +
                      "name, !name, name=value, name!=value or any of them concatenated by '||')",
                      predicateExpr);

        return new RoutingPredicate<>(routingPredicate.name, routingPredicate.delegate, false);
    }

    @Nullable
    private static <T, U> RoutingPredicate<T> parseRoutingPredicate(
            String predicateExpr, Function<String, U> nameConverter,
            Function<U, Predicate<T>> containsPredicate,
            BiFunction<U, String, Predicate<T>> equalsPredicate) {

        RoutingPredicate<T> result = null;
        for (String expr : OR_PATTERN.split(predicateExpr, -1)) {
            if (expr.isEmpty()) {
                // Contains an empty expression.
                return null;
            }

            final RoutingPredicate<T> predicate =
                    parseSingleRoutingPredicate(expr,
                                                nameConverter,
                                                containsPredicate,
                                                equalsPredicate);
            if (predicate == null) {
                return null;
            }

            if (result == null) {
                result = predicate;
            } else {
                result = or(result, predicate);
            }
        }

        return result;
    }

    @Nullable
    private static <T, U> RoutingPredicate<T> parseSingleRoutingPredicate(
            String predicateExpr, Function<String, U> nameConverter,
            Function<U, Predicate<T>> containsPredicate,
            BiFunction<U, String, Predicate<T>> equalsPredicate) {
        if (predicateExpr.contains("|")) {
            // `|` should appear only in the or-form such as `foo || bar`.
            return null;
        }
        final RoutingPredicate<T> routingPredicate;
        final RoutingPredicate<T> containRoutingPredicate =
                parseContainRoutingPredicate(predicateExpr, nameConverter, containsPredicate);
        if (containRoutingPredicate != null) {
            routingPredicate = containRoutingPredicate;
        } else {
            routingPredicate = parseCompareRoutingPredicate(predicateExpr, nameConverter, equalsPredicate);
        }
        return routingPredicate;
    }

    @Nullable
    private static <T, U> RoutingPredicate<T> parseContainRoutingPredicate(
            String predicateExpr, Function<String, U> nameConverter,
            Function<U, Predicate<T>> containsPredicate) {
        final Matcher containMatcher = CONTAIN_PATTERN.matcher(predicateExpr);
        if (containMatcher.matches()) {
            final U name = nameConverter.apply(containMatcher.group(2));
            final Predicate<T> predicate = containsPredicate.apply(name);
            if ("!".equals(containMatcher.group(1))) {
                return negated(containMatcher.group(2), predicate);
            } else {
                return from(predicateExpr, predicate);
            }
        }
        return null;
    }

    @Nullable
    private static <T, U> RoutingPredicate<T> parseCompareRoutingPredicate(
            String predicateExpr, Function<String, U> nameConverter,
            BiFunction<U, String, Predicate<T>> equalsPredicate) {
        final Matcher compareMatcher = COMPARE_PATTERN.matcher(predicateExpr);
        if (compareMatcher.matches()) {
            assert compareMatcher.groupCount() == 3;

            final String name = compareMatcher.group(1);
            final String comparator = compareMatcher.group(2);
            final String value = compareMatcher.group(3);

            final Predicate<T> predicate = equalsPredicate.apply(nameConverter.apply(name), value);
            final String noWsValue = WHITESPACE_PATTERN.matcher(value).replaceAll("_");
            switch (comparator) {
                case "=":
                    return eq(name, noWsValue, predicate);
                case "!=":
                    return notEq(name, noWsValue, predicate);
            }
        }
        return null;
    }

    private final CharSequence name;
    private final Predicate<T> delegate;
    private final boolean customPredicate;

    RoutingPredicate(CharSequence name, Predicate<T> delegate, boolean customPredicate) {
        this.name = requireNonNull(name, "name");
        this.delegate = requireNonNull(delegate, "delegate");
        this.customPredicate = customPredicate;
    }

    CharSequence name() {
        return name;
    }

    boolean isCustomPredicate() {
        return customPredicate;
    }

    /**
     * Tests the specified {@code t} object.
     *
     * @see DefaultRoute where this predicate is evaluated
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
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof RoutingPredicate)) {
            return false;
        }

        final RoutingPredicate<?> that = (RoutingPredicate<?>) o;
        return name.equals(that.name) &&
               delegate.equals(that.delegate);
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + delegate.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("delegate", delegate)
                          .toString();
    }

    private static <T> RoutingPredicate<T> eq(String name, String value, Predicate<T> predicate) {
        return new RoutingPredicate<>(name + "_eq_" + value, predicate, false);
    }

    private static <T> RoutingPredicate<T> notEq(String name, String value, Predicate<T> predicate) {
        return new RoutingPredicate<>(name + "_ne_" + value, predicate.negate(), false);
    }

    private static <T> RoutingPredicate<T> negated(String name, Predicate<T> predicate) {
        return new RoutingPredicate<>("not_" + name, predicate.negate(), false);
    }

    private static <T> RoutingPredicate<T> from(String name, Predicate<T> predicate) {
        return new RoutingPredicate<>(name, predicate, false);
    }

    private static <T> RoutingPredicate<T> or(RoutingPredicate<T> current, RoutingPredicate<T> other) {
        final String currentName = String.valueOf(current.name);
        if (currentName.isEmpty()) {
            return new RoutingPredicate<>(other.name + "_or_", current.delegate.or(other.delegate), false);
        } else {
            if (currentName.endsWith("_or_")) {
                return new RoutingPredicate<>(currentName + other.name,
                                              current.delegate.or(other.delegate), false);
            }
            return new RoutingPredicate<>(currentName + "_or_" + other.name,
                                          current.delegate.or(other.delegate), false);
        }
    }
}
