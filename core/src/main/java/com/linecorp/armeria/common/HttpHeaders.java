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

import java.time.Instant;
import java.time.temporal.TemporalAccessor;
import java.util.Calendar;
import java.util.Date;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Immutable HTTP/2 headers.
 *
 * <h2>Building a new {@link HttpHeaders}</h2>
 *
 * <p>You can use the {@link HttpHeaders#of(CharSequence, String) HttpHeaders.of()} factory methods or
 * the {@link HttpHeadersBuilder} to build a new {@link HttpHeaders} from scratch:</p>
 *
 * <pre>{@code
 * // Using of()
 * HttpHeaders headersWithOf =
 *     HttpHeaders.of(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8",
 *                    HttpHeaderNames.CONTENT_LENGTH, "42");
 *
 * // Using builder()
 * HttpHeaders headersWithBuilder =
 *     HttpHeaders.builder()
 *                .add(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
 *                .add(HttpHeaderNames.CONTENT_LENGTH, "42")
 *                .build();
 *
 * assert headersWithOf.equals(headersWithBuilder);
 * }</pre>
 *
 * <h2>Building a new {@link HttpHeaders} from an existing one</h2>
 *
 * <p>You can use {@link HttpHeaders#toBuilder()} or {@link HttpHeaders#withMutations(Consumer)} to build
 * a new {@link HttpHeaders} derived from an existing one:</p>
 *
 * <pre>{@code
 * HttpHeaders headers = HttpHeaders.of("name1", "value0");
 *
 * // Using toBuilder()
 * HttpHeaders headersWithToBuilder = headers.toBuilder()
 *                                           .set("name1", "value1")
 *                                           .add("name2", "value2")
 *                                           .build();
 * // Using withMutations()
 * HttpHeaders headersWithMutations = headers.withMutations(builder -> {
 *     builder.set("name1", "value1");
 *     builder.add("name2", "value2");
 * });
 *
 * assert headersWithToBuilder.equals(headersWithMutations);
 *
 * // Note that the original headers remain unmodified.
 * assert !headers.equals(headersWithToBuilder);
 * assert !headers.equals(headersWithMutations);
 * }</pre>
 *
 * <h2><a id="object-values">Specifying a non-{@link String} header value</a></h2>
 *
 * <p>Certain header values are better represented as a Java object than as a {@link String}.
 * For example, it is more convenient to specify {@code "content-length"}, {@code "content-type"} and
 * {@code "date"} header as {@link Integer}, {@link MediaType} and {@link Instant} (or {@link Date})
 * respectively. Armeria's HTTP header API allows you to specify a Java object of well-known type
 * as a header value by converting it into an HTTP-friendly {@link String} representation:</p>
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
 *         <a href="https://tools.ietf.org/html/rfc1123#page-55">RFC1123</a></li>
 *       <li>e.g. {@code Sun, 27 Nov 2016 19:37:15 UTC}</li>
 *     </ul>
 *   </li>
 *   <li>All other types
 *     <ul><li>Converted via {@code toString()}</li></ul>
 *   </li>
 * </ul>
 *
 * <h2>Using {@link HttpHeaders#of(CharSequence, Object) HttpHeaders.of()} factory methods</h2>
 *
 * <pre>{@code
 * HttpHeaders headers =
 *     HttpHeaders.of(HttpHeaderNames.CONTENT_LENGTH, 42,
 *                    HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
 *                    HttpHeaderNames.DATE, Instant.now());
 * }</pre>
 *
 * <h2>Using {@link HttpHeadersBuilder}</h2>
 *
 * <pre>{@code
 * HttpHeaders headers =
 *     HttpHeaders.builder()
 *                .setObject(HttpHeaderNames.CONTENT_LENGTH, 42)
 *                .setObject(HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8)
 *                .setObject(HttpHeaderNames.DATE, Instant.now())
 *                .build();
 * }</pre>
 *
 * <h3>Specifying value type explicitly</h3>
 *
 * <p>You might prefer type-safe setters for more efficiency and less ambiguity:</p>
 *
 * <pre>{@code
 * HttpHeaders headers =
 *     HttpHeaders.builder()
 *                .setInt(HttpHeaderNames.CONTENT_LENGTH, 42)
 *                .set(HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8.toString())
 *                .setTimeMillis(HttpHeaderNames.DATE, System.currentTimeMillis())
 *                .build();
 * }</pre>
 *
 * @see RequestHeaders
 * @see ResponseHeaders
 */
@JsonSerialize(using = HttpHeadersJsonSerializer.class)
@JsonDeserialize(using = HttpHeadersJsonDeserializer.class)
public interface HttpHeaders extends HttpObject, HttpHeaderGetters {

    /**
     * Returns a new empty builder.
     */
    static HttpHeadersBuilder builder() {
        return new DefaultHttpHeadersBuilder();
    }

    /**
     * Returns an empty {@link HttpHeaders}.
     */
    static HttpHeaders of() {
        return DefaultHttpHeaders.EMPTY;
    }

    /**
     * Returns a new {@link HttpHeaders} with the specified header.
     */
    static HttpHeaders of(CharSequence name, String value) {
        return builder().add(name, value).build();
    }

    /**
     * Returns a new {@link HttpHeaders} with the specified header. The value is converted into
     * a {@link String} as explained in <a href="#object-values">Specifying a non-String header value</a>.
     */
    static HttpHeaders of(CharSequence name, Object value) {
        return builder().addObject(name, value).build();
    }

    /**
     * Returns a new {@link HttpHeaders} with the specified headers.
     */
    static HttpHeaders of(CharSequence name1, String value1,
                          CharSequence name2, String value2) {
        return builder().add(name1, value1)
                        .add(name2, value2)
                        .build();
    }

    /**
     * Returns a new {@link HttpHeaders} with the specified headers. The values are converted into
     * {@link String}s as explained in <a href="#object-values">Specifying a non-String header value</a>.
     */
    static HttpHeaders of(CharSequence name1, Object value1,
                          CharSequence name2, Object value2) {
        return builder().addObject(name1, value1)
                        .addObject(name2, value2)
                        .build();
    }

    /**
     * Returns a new {@link HttpHeaders} with the specified headers.
     */
    static HttpHeaders of(CharSequence name1, String value1,
                          CharSequence name2, String value2,
                          CharSequence name3, String value3) {
        return builder().add(name1, value1)
                        .add(name2, value2)
                        .add(name3, value3)
                        .build();
    }

    /**
     * Returns a new {@link HttpHeaders} with the specified headers. The values are converted into
     * {@link String}s as explained in <a href="#object-values">Specifying a non-String header value</a>.
     */
    static HttpHeaders of(CharSequence name1, Object value1,
                          CharSequence name2, Object value2,
                          CharSequence name3, Object value3) {
        return builder().addObject(name1, value1)
                        .addObject(name2, value2)
                        .addObject(name3, value3)
                        .build();
    }

    /**
     * Returns a new {@link HttpHeaders} with the specified headers.
     */
    static HttpHeaders of(CharSequence name1, String value1,
                          CharSequence name2, String value2,
                          CharSequence name3, String value3,
                          CharSequence name4, String value4) {
        return builder().add(name1, value1)
                        .add(name2, value2)
                        .add(name3, value3)
                        .add(name4, value4)
                        .build();
    }

    /**
     * Returns a new {@link HttpHeaders} with the specified headers. The values are converted into
     * {@link String}s as explained in <a href="#object-values">Specifying a non-String header value</a>.
     */
    static HttpHeaders of(CharSequence name1, Object value1,
                          CharSequence name2, Object value2,
                          CharSequence name3, Object value3,
                          CharSequence name4, Object value4) {
        return builder().addObject(name1, value1)
                        .addObject(name2, value2)
                        .addObject(name3, value3)
                        .addObject(name4, value4)
                        .build();
    }

    /**
     * Returns a new builder created from the entries of this headers.
     *
     * @see #withMutations(Consumer)
     */
    HttpHeadersBuilder toBuilder();

    /**
     * Returns a new headers which is the result from the mutation by the specified {@link Consumer}.
     * This method is a shortcut for:
     * <pre>{@code
     * builder = toBuilder();
     * mutator.accept(builder);
     * return builder.build();
     * }</pre>
     *
     * @see #toBuilder()
     */
    default HttpHeaders withMutations(Consumer<HttpHeadersBuilder> mutator) {
        final HttpHeadersBuilder builder = toBuilder();
        mutator.accept(builder);
        return builder.build();
    }
}
