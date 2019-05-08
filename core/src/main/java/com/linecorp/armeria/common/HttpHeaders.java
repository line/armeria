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

import java.util.function.Consumer;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

/**
 * Immutable HTTP/2 headers.
 *
 * @see RequestHeaders
 * @see ResponseHeaders
 */
@JsonSerialize(using = HttpHeadersJsonSerializer.class)
@JsonDeserialize(using = HttpHeadersJsonDeserializer.class)
public interface HttpHeaders extends HttpObject, HttpHeaderGetters {

    /**
     * An empty {@link HttpHeaders}.
     *
     * @deprecated Use {@link #of()}.
     */
    @Deprecated
    HttpHeaders EMPTY_HEADERS = of();

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
     * a {@link String} via {@link HttpHeadersBase#addObject(CharSequence, Object)}.
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
     * {@link String}s via {@link HttpHeadersBase#addObject(CharSequence, Object)}.
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
     * {@link String}s via {@link HttpHeadersBase#addObject(CharSequence, Object)}.
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
     * {@link String}s via {@link HttpHeadersBase#addObject(CharSequence, Object)}.
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
     * This method is a shortcut of:
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
