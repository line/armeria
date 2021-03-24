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
package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Date;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.common.base.Strings;

import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * Immutable HTTP query parameters.
 *
 * <h2>Building a new {@link QueryParams}</h2>
 *
 * <p>You can use the {@link QueryParams#of(String, String) QueryParams.of()} factory methods or
 * the {@link QueryParamsBuilder} to build a new {@link QueryParams} from scratch:</p>
 *
 * <pre>{@code
 * // Using of()
 * QueryParams paramsWithOf = QueryParams.of("the_string", "fourty-two",
 *                                           "the_number", 42);
 *
 * // Using builder()
 * QueryParams paramsWithBuilder =
 *     QueryParams.builder()
 *                .add("the_string", "forty-two")
 *                .add("the_number", 42)
 *                .build();
 *
 * assert paramsWithOf.equals(paramsWithBuilder);
 * }</pre>
 *
 * <h2>Building a new {@link QueryParams} from an existing one</h2>
 *
 * <p>You can use {@link QueryParams#toBuilder()} or {@link QueryParams#withMutations(Consumer)} to build
 * a new {@link QueryParams} derived from an existing one:</p>
 *
 * <pre>{@code
 * QueryParams params = QueryParams.of("name1", "value0");
 *
 * // Using toBuilder()
 * QueryParams paramsWithToBuilder = params.toBuilder()
 *                                         .set("name1", "value1")
 *                                         .add("name2", "value2")
 *                                         .build();
 * // Using withMutations()
 * QueryParams paramsWithMutations = params.withMutations(builder -> {
 *     builder.set("name1", "value1");
 *     builder.add("name2", "value2");
 * });
 *
 * assert paramsWithToBuilder.equals(paramsWithMutations);
 *
 * // Note that the original parameters remain unmodified.
 * assert !params.equals(paramsWithToBuilder);
 * assert !params.equals(paramsWithMutations);
 * }</pre>
 *
 * <h2><a id="object-values">Specifying a non-{@link String} parameter value</a></h2>
 *
 * <p>Certain parameter values are better represented as a Java object, such as {@link Integer},
 * {@link MediaType}, {@link Instant} or {@link Date}, than as a {@link String}. Armeria's query parameters
 * API allows you to specify a Java object of well-known type as a parameter value by converting it into
 * an HTTP-friendly {@link String} representation:</p>
 *
 * <ul>
 *   <li>{@link Number}, {@link CharSequence} and {@link MediaType}
 *     <ul>
 *       <li>Converted via {@code toString()}</li>
 *       <li>e.g. {@code "42"}, {@code "string"}, {@code "text/plain; charset=utf-8"}</li>
 *     </ul>
 *   </li>
 *   <li>{@link CacheControl} and {@link ContentDisposition}
 *     <ul>
 *       <li>Converted via {@code asHeaderValue()}</li>
 *       <li>e.g. {@code "no-cache, no-store, must-revalidate"}, {@code "form-data; name=\"fieldName\""}</li>
 *     </ul>
 *   </li>
 *   <li>{@link Instant}, {@link TemporalAccessor}, {@link Date} and {@link Calendar}
 *     <ul>
 *       <li>Converted into a time and date string as specified in
 *         <a href="https://datatracker.ietf.org/doc/html/rfc1123#page-55">RFC1123</a></li>
 *       <li>e.g. {@code Sun, 27 Nov 2016 19:37:15 UTC}</li>
 *     </ul>
 *   </li>
 *   <li>All other types
 *     <ul><li>Converted via {@code toString()}</li></ul>
 *   </li>
 * </ul>
 *
 * <h2>Using {@link QueryParams#of(String, Object) QueryParams.of()} factory methods</h2>
 *
 * <pre>{@code
 * QueryParams params = QueryParams.of("the-number", 42,
 *                                     "the-media-type", MediaType.JSON_UTF_8,
 *                                     "the-date", Instant.now());
 * }</pre>
 *
 * <h3>Using {@link QueryParamsBuilder}</h3>
 *
 * <pre>{@code
 * QueryParams params =
 *     QueryParams.builder()
 *                .setObject("the-number", 42)
 *                .setObject("the-media-type", MediaType.JSON_UTF_8)
 *                .setObject("the-date", Instant.now())
 *                .build();
 * }</pre>
 *
 * <h4>Specifying value type explicitly</h4>
 *
 * <p>You might prefer type-safe setters for more efficiency and less ambiguity:</p>
 *
 * <pre>{@code
 * QueryParams params =
 *     QueryParams.builder()
 *                .setInt("the-number", 42)
 *                .set("the-media-type", MediaType.JSON_UTF_8.toString())
 *                .setTimeMillis("the-date", System.currentTimeMillis())
 *                .build();
 * }</pre>
 */
public interface QueryParams extends QueryParamGetters {

    /**
     * Returns a new empty builder.
     */
    static QueryParamsBuilder builder() {
        return new DefaultQueryParamsBuilder();
    }

    /**
     * Returns an empty {@link QueryParams}.
     */
    static QueryParams of() {
        return DefaultQueryParams.EMPTY;
    }

    /**
     * Returns a new {@link QueryParams} with the specified parameter.
     */
    static QueryParams of(String name, String value) {
        return builder().add(name, value).build();
    }

    /**
     * Returns a new {@link QueryParams} with the specified parameter. The value is converted into
     * a {@link String} as explained in <a href="#object-values">Specifying a non-String parameter value</a>.
     */
    static QueryParams of(String name, Object value) {
        return builder().addObject(name, value).build();
    }

    /**
     * Returns a new {@link QueryParams} with the specified parameters.
     */
    static QueryParams of(String name1, String value1,
                          String name2, String value2) {
        return builder().add(name1, value1)
                        .add(name2, value2)
                        .build();
    }

    /**
     * Returns a new {@link QueryParams} with the specified parameters. The values are converted into
     * {@link String}s as explained in <a href="#object-values">Specifying a non-String parameter value</a>.
     */
    static QueryParams of(String name1, Object value1,
                          String name2, Object value2) {
        return builder().addObject(name1, value1)
                        .addObject(name2, value2)
                        .build();
    }

    /**
     * Returns a new {@link QueryParams} with the specified parameters.
     */
    static QueryParams of(String name1, String value1,
                          String name2, String value2,
                          String name3, String value3) {
        return builder().add(name1, value1)
                        .add(name2, value2)
                        .add(name3, value3)
                        .build();
    }

    /**
     * Returns a new {@link QueryParams} with the specified parameters. The values are converted into
     * {@link String}s as explained in <a href="#object-values">Specifying a non-String parameter value</a>.
     */
    static QueryParams of(String name1, Object value1,
                          String name2, Object value2,
                          String name3, Object value3) {
        return builder().addObject(name1, value1)
                        .addObject(name2, value2)
                        .addObject(name3, value3)
                        .build();
    }

    /**
     * Returns a new {@link QueryParams} with the specified parameters.
     */
    static QueryParams of(String name1, String value1,
                          String name2, String value2,
                          String name3, String value3,
                          String name4, String value4) {
        return builder().add(name1, value1)
                        .add(name2, value2)
                        .add(name3, value3)
                        .add(name4, value4)
                        .build();
    }

    /**
     * Returns a new {@link QueryParams} with the specified parameters. The values are converted into
     * {@link String}s as explained in <a href="#object-values">Specifying a non-String parameter value</a>.
     */
    static QueryParams of(String name1, Object value1,
                          String name2, Object value2,
                          String name3, Object value3,
                          String name4, Object value4) {
        return builder().addObject(name1, value1)
                        .addObject(name2, value2)
                        .addObject(name3, value3)
                        .addObject(name4, value4)
                        .build();
    }

    /**
     * Decodes the specified query string into a {@link QueryParams}, as defined in
     * <a href="https://www.w3.org/TR/2014/REC-html5-20141028/forms.html#url-encoded-form-data">4.10.22.6,
     * HTML5 W3C Recommendation</a>.
     *
     * @param queryString the query string without leading question mark ({@code '?'}).
     * @return the decoded {@link QueryParams}. An empty {@link QueryParams} is returned
     *         if {@code queryString} is {@code null}.
     */
    static QueryParams fromQueryString(@Nullable String queryString) {
        return fromQueryString(queryString, 1024);
    }

    /**
     * Decodes the specified query string into a {@link QueryParams}, as defined in
     * <a href="https://www.w3.org/TR/2014/REC-html5-20141028/forms.html#url-encoded-form-data">4.10.22.6,
     * HTML5 W3C Recommendation</a>.
     *
     * @param queryString the query string without leading question mark ({@code '?'}).
     * @param maxParams   the max number of parameters to decode. If the {@code queryString} contains
     *                    more parameters than this value, the extra parameters will not be decoded.
     * @return the decoded {@link QueryParams}. An empty {@link QueryParams} is returned
     *         if {@code queryString} is {@code null}.
     */
    static QueryParams fromQueryString(@Nullable String queryString, int maxParams) {

        // Do not treat a semicolon (;) as a separator by default, as recommended in:
        //
        //   https://www.w3.org/TR/2014/REC-html5-20141028/forms.html#url-encoded-form-data
        //
        // > Let strings be the result of strictly splitting the string payload on
        // > U+0026 AMPERSAND characters (&).

        return fromQueryString(queryString, maxParams, /* semicolonAsSeparator */ false);
    }

    /**
     * Decodes the specified query string into a {@link QueryParams}, as defined in
     * <a href="https://www.w3.org/TR/2014/REC-html5-20141028/forms.html#url-encoded-form-data">4.10.22.6,
     * HTML5 W3C Recommendation</a>.
     *
     * @param queryString the query string without leading question mark ({@code '?'}).
     * @param semicolonAsSeparator whether to treat a semicolon ({@code ';'}) as a separator as well as
     *                             an ampersand ({@code '&'}). Note that HTML5 expects you to use only
     *                             ampersand as a separator. Enable this flag only when you need to
     *                             interop with a legacy system.
     * @return the decoded {@link QueryParams}. An empty {@link QueryParams} is returned
     *         if {@code queryString} is {@code null}.
     */
    static QueryParams fromQueryString(@Nullable String queryString, boolean semicolonAsSeparator) {
        return fromQueryString(queryString, 1024, semicolonAsSeparator);
    }

    /**
     * Decodes the specified query string into a {@link QueryParams}, as defined in
     * <a href="https://www.w3.org/TR/2014/REC-html5-20141028/forms.html#url-encoded-form-data">4.10.22.6,
     * HTML5 W3C Recommendation</a>.
     *
     * @param queryString the query string without leading question mark ({@code '?'}).
     * @param maxParams   the max number of parameters to decode. If the {@code queryString} contains
     *                    more parameters than this value, the extra parameters will not be decoded.
     * @param semicolonAsSeparator whether to treat a semicolon ({@code ';'}) as a separator as well as
     *                             an ampersand ({@code '&'}). Note that HTML5 expects you to use only
     *                             ampersand as a separator. Enable this flag only when you need to
     *                             interop with a legacy system.
     * @return the decoded {@link QueryParams}. An empty {@link QueryParams} is returned
     *         if {@code queryString} is {@code null}.
     */
    static QueryParams fromQueryString(@Nullable String queryString, int maxParams,
                                       boolean semicolonAsSeparator) {
        if (Strings.isNullOrEmpty(queryString)) {
            return of();
        }

        return QueryStringDecoder.decodeParams(TemporaryThreadLocals.get(),
                                               queryString, maxParams, semicolonAsSeparator);
    }

    /**
     * Returns a new builder created from the entries of this parameters.
     *
     * @see #withMutations(Consumer)
     */
    QueryParamsBuilder toBuilder();

    /**
     * Returns new parameters which is the result from the mutation by the specified {@link Consumer}.
     * This method is a shortcut for:
     * <pre>{@code
     * builder = toBuilder();
     * mutator.accept(builder);
     * return builder.build();
     * }</pre>
     *
     * @see #toBuilder()
     */
    default QueryParams withMutations(Consumer<QueryParamsBuilder> mutator) {
        requireNonNull(mutator, "mutator");
        final QueryParamsBuilder builder = toBuilder();
        mutator.accept(builder);
        return builder.build();
    }
}
