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

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

/**
 * Builds an {@link HttpHeaders}.
 *
 * @see HttpHeaders#builder()
 * @see HttpHeaders#toBuilder()
 */
public interface HttpHeadersBuilder extends HttpHeaderGetters {
    /**
     * Returns a newly created {@link HttpHeaders} with the entries in this builder.
     */
    HttpHeaders build();

    /**
     * Specifies the hint about the number of headers which may improve the memory efficiency and performance
     * of the underlying data structure.
     *
     * @return {@code this}
     * @throws IllegalStateException if the hint was specified too late after the underlying data structure
     *                               has been fully initialized.
     */
    HttpHeadersBuilder sizeHint(int sizeHint);

    /**
     * Sets whether the headers will be the last frame in an HTTP/2 stream.
     */
    HttpHeadersBuilder endOfStream(boolean endOfStream);

    /**
     * Sets the <a href="https://datatracker.ietf.org/doc/html/rfc2616#section-14.13">content-length</a> header.
     */
    HttpHeadersBuilder contentLength(long contentLength);

    /**
     * Sets the {@code "content-type"} header.
     */
    HttpHeadersBuilder contentType(MediaType contentType);

    /**
     * Sets the {@code "content-disposition"} header.
     */
    HttpHeadersBuilder contentDisposition(ContentDisposition contentDisposition);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @return the first header value or {@code null} if there is no such header
     */
    @Nullable
    String getAndRemove(CharSequence name);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @param defaultValue the default value
     * @return the first header value or {@code defaultValue} if there is no such header
     */
    String getAndRemove(CharSequence name, String defaultValue);

    /**
     * Removes all the headers with the specified name and returns the removed header values.
     *
     * @param name the header name
     * @return a {@link List} of header values or an empty {@link List} if no values are found.
     */
    List<String> getAllAndRemove(CharSequence name);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @return the {@code int} value of the first value in insertion order or {@code null} if there is no
     *         such value or it can't be converted into {@code int}.
     */
    @Nullable
    Integer getIntAndRemove(CharSequence name);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @param defaultValue the default value
     * @return the {@code int} value of the first value in insertion order or {@code defaultValue} if there is
     *         no such value or it can't be converted into {@code int}.
     */
    int getIntAndRemove(CharSequence name, int defaultValue);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @return the {@code long} value of the first value in insertion order or {@code null} if there is no such
     *         value or it can't be converted into {@code long}.
     */
    @Nullable
    Long getLongAndRemove(CharSequence name);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @param defaultValue the default value
     * @return the {@code long} value of the first value in insertion order or {@code defaultValue} if there is
     *         no such value or it can't be converted into {@code long}.
     */
    long getLongAndRemove(CharSequence name, long defaultValue);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @return the {@code float} value of the first value in insertion order or {@code null} if there is
     *         no such value or it can't be converted into {@code float}.
     */
    @Nullable
    Float getFloatAndRemove(CharSequence name);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @param defaultValue the default value
     * @return the {@code float} value of the first value in insertion order or {@code defaultValue} if there
     *         is no such value or it can't be converted into {@code float}.
     */
    float getFloatAndRemove(CharSequence name, float defaultValue);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @return the {@code double} value of the first value in insertion order or {@code null} if there is
     *         no such value or it can't be converted into {@code double}.
     */
    @Nullable
    Double getDoubleAndRemove(CharSequence name);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @param defaultValue the default value
     * @return the {@code double} value of the first value in insertion order or {@code defaultValue} if there
     *         is no such value or it can't be converted into {@code double}.
     */
    double getDoubleAndRemove(CharSequence name, double defaultValue);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @return the milliseconds value of the first value in insertion order or {@code null} if there is no such
     *         value or it can't be converted into milliseconds.
     */
    @Nullable
    Long getTimeMillisAndRemove(CharSequence name);

    /**
     * Removes all the headers with the specified name and returns the header value which was added first.
     *
     * @param name the header name
     * @param defaultValue the default value
     * @return the milliseconds value of the first value in insertion order or {@code defaultValue} if there is
     *         no such value or it can't be converted into milliseconds.
     */
    long getTimeMillisAndRemove(CharSequence name, long defaultValue);

    /**
     * Adds a new header with the specified {@code name} and {@code value}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder add(CharSequence name, String value);

    /**
     * Adds new headers with the specified {@code name} and {@code values}. This method is semantically
     * equivalent to
     * <pre>{@code
     * for (String value : values) {
     *     builder.add(name, value);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    HttpHeadersBuilder add(CharSequence name, Iterable<String> values);

    /**
     * Adds new headers with the specified {@code name} and {@code values}. This method is semantically
     * equivalent to
     * <pre>{@code
     * for (String value : values) {
     *     builder.add(name, value);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    HttpHeadersBuilder add(CharSequence name, String... values);

    /**
     * Adds all header names and values of the specified {@code entries}.
     *
     * @return {@code this}
     * @throws IllegalArgumentException if {@code entries == this}.
     */
    HttpHeadersBuilder add(Iterable<? extends Entry<? extends CharSequence, String>> entries);

    /**
     * Adds all header names and values of the specified {@code entries}.
     *
     * @return {@code this}
     */
    default HttpHeadersBuilder add(Map<? extends CharSequence, String> entries) {
        requireNonNull(entries, "entries");
        return add(entries.entrySet());
    }

    /**
     * Adds a new header. The specified header value is converted into a {@link String}, as explained
     * in <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder addObject(CharSequence name, Object value);

    /**
     * Adds a new header with the specified name and values. The specified header values are converted into
     * {@link String}s, as explained in <a href="HttpHeaders.html#object-values">Specifying a non-String
     * header value</a>. This method is equivalent to:
     * <pre>{@code
     * for (Object v : values) {
     *     builder.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    HttpHeadersBuilder addObject(CharSequence name, Iterable<?> values);

    /**
     * Adds a new header with the specified name and values. The specified header values are converted into
     * {@link String}s, as explained in <a href="HttpHeaders.html#object-values">Specifying a non-String
     * header value</a>. This method is equivalent to:
     * <pre>{@code
     * for (Object v : values) {
     *     builder.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    HttpHeadersBuilder addObject(CharSequence name, Object... values);

    /**
     * Adds all header names and values of the specified {@code entries}. The specified header values are
     * converted into {@link String}s, as explained in <a href="HttpHeaders.html#object-values">Specifying
     * a non-String header value</a>.
     *
     * @return {@code this}
     * @throws IllegalArgumentException if {@code entries == this}.
     */
    HttpHeadersBuilder addObject(Iterable<? extends Entry<? extends CharSequence, ?>> entries);

    /**
     * Adds a new header.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder addInt(CharSequence name, int value);

    /**
     * Adds a new header.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder addLong(CharSequence name, long value);

    /**
     * Adds a new header.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder addFloat(CharSequence name, float value);

    /**
     * Adds a new header.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder addDouble(CharSequence name, double value);

    /**
     * Adds a new header.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder addTimeMillis(CharSequence name, long value);

    /**
     * Sets a header with the specified name and value. Any existing headers with the same name are
     * overwritten.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder set(CharSequence name, String value);

    /**
     * Sets a new header with the specified name and values. This method is equivalent to
     * <pre>{@code
     * builder.remove(name);
     * for (String v : values) {
     *     builder.add(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    HttpHeadersBuilder set(CharSequence name, Iterable<String> values);

    /**
     * Sets a header with the specified name and values. Any existing headers with the specified name are
     * removed. This method is equivalent to:
     * <pre>{@code
     * builder.remove(name);
     * for (String v : values) {
     *     builder.add(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the header values
     * @return {@code this}
     */
    HttpHeadersBuilder set(CharSequence name, String... values);

    /**
     * Retains all current headers but calls {@link #set(CharSequence, String)} for each header in
     * the specified {@code entries}.
     *
     * @param entries the headers used to set the header values
     * @return {@code this}
     */
    HttpHeadersBuilder set(Iterable<? extends Entry<? extends CharSequence, String>> entries);

    /**
     * Retains all current headers but calls {@link #set(CharSequence, String)} for each header in
     * the specified {@code entries}.
     *
     * @param entries the headers used to set the header values
     * @return {@code this}
     */
    default HttpHeadersBuilder set(Map<? extends CharSequence, String> entries) {
        requireNonNull(entries, "entries");
        return set(entries.entrySet());
    }

    /**
     * Copies the entries missing in this headers from the specified {@code entries}.
     * This method is a shortcut for:
     * <pre>{@code
     * headers.names().forEach(name -> {
     *     if (!builder.contains(name)) {
     *         builder.set(name, headers.getAll(name));
     *     }
     * });
     * }</pre>
     *
     * @return {@code this}
     */
    HttpHeadersBuilder setIfAbsent(Iterable<? extends Entry<? extends CharSequence, String>> entries);

    /**
     * Sets a new header. Any existing headers with the specified name are removed. The specified header value
     * is converted into a {@link String}, as explained in <a href="HttpHeaders.html#object-values">Specifying
     * a non-String header value</a>.
     *
     * @param name the header name
     * @param value the value of the header
     * @return {@code this}
     */
    HttpHeadersBuilder setObject(CharSequence name, Object value);

    /**
     * Sets a header with the specified name and values. Any existing headers with the specified name are
     * removed. The specified header values are converted into {@link String}s, as explained in
     * <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     * This method is equivalent to:
     * <pre>{@code
     * builder.remove(name);
     * for (Object v : values) {
     *     builder.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the values of the header
     * @return {@code this}
     */
    HttpHeadersBuilder setObject(CharSequence name, Iterable<?> values);

    /**
     * Sets a header with the specified name and values. Any existing headers with the specified name are
     * removed. The specified header values are converted into {@link String}s, as explained in
     * <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     * This method is equivalent to:
     * <pre>{@code
     * builder.remove(name);
     * for (Object v : values) {
     *     builder.addObject(name, v);
     * }
     * }</pre>
     *
     * @param name the header name
     * @param values the values of the header
     * @return {@code this}
     */
    HttpHeadersBuilder setObject(CharSequence name, Object... values);

    /**
     * Retains all current headers but calls {@link #setObject(CharSequence, Object)} for each entry in
     * the specified {@code entries}. The specified header values are converted into {@link String}s,
     * as explained in <a href="HttpHeaders.html#object-values">Specifying a non-String header value</a>.
     *
     * @param entries the headers used to set the values in this instance
     * @return {@code this}
     */
    HttpHeadersBuilder setObject(Iterable<? extends Entry<? extends CharSequence, ?>> entries);

    /**
     * Sets a header with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder setInt(CharSequence name, int value);

    /**
     * Sets a header with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder setLong(CharSequence name, long value);

    /**
     * Sets a header with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder setFloat(CharSequence name, float value);

    /**
     * Sets a header with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder setDouble(CharSequence name, double value);

    /**
     * Sets a header with the specified {@code name} to {@code value}. This will remove all previous values
     * associated with {@code name}.
     *
     * @param name the header name
     * @param value the header value
     * @return {@code this}
     */
    HttpHeadersBuilder setTimeMillis(CharSequence name, long value);

    /**
     * Removes all headers with the specified {@code name}.
     *
     * @param name the header name
     * @return {@code true} if at least one entry has been removed.
     */
    boolean remove(CharSequence name);

    /**
     * Removes all headers with the specified {@code name}. Unlike {@link #remove(CharSequence)}
     * this method returns itself so that the caller can chain the invocations.
     *
     * @param name the header name
     * @return {@code this}
     */
    HttpHeadersBuilder removeAndThen(CharSequence name);

    /**
     * Removes all headers. After a call to this method, {@link #size()} becomes {@code 0}.
     *
     * @return {@code this}
     */
    HttpHeadersBuilder clear();
}
