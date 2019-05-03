package com.linecorp.armeria.common;

import java.util.Map.Entry;

/**
 * Builds a {@link ResponseHeaders}.
 *
 * @see ResponseHeaders#builder()
 * @see ResponseHeaders#toBuilder()
 */
public interface ResponseHeadersBuilder extends HttpHeadersBuilder, ResponseHeaderGetters {
    /**
     * Returns a newly created {@link ResponseHeaders} with the entries in this builder.
     * Note that any further modification of this builder is prohibited after this method is invoked.
     *
     * @throws IllegalStateException if this builder does not have {@code ":status"} header set.
     */
    @Override
    ResponseHeaders build();

    /**
     * Sets the the {@code ":status"} header.
     */
    ResponseHeadersBuilder status(int statusCode);

    /**
     * Sets the the {@code ":status"} header.
     */
    ResponseHeadersBuilder status(HttpStatus status);

    // Override the return type of the chaining methods in the superclass.

    @Override
    ResponseHeadersBuilder sizeHint(int sizeHint);

    @Override
    ResponseHeadersBuilder endOfStream(boolean endOfStream);

    @Override
    ResponseHeadersBuilder contentType(MediaType contentType);

    @Override
    ResponseHeadersBuilder add(CharSequence name, String value);

    @Override
    ResponseHeadersBuilder add(CharSequence name, Iterable<String> values);

    @Override
    ResponseHeadersBuilder add(CharSequence name, String... values);

    @Override
    ResponseHeadersBuilder add(Iterable<? extends Entry<? extends CharSequence, String>> headers);

    @Override
    ResponseHeadersBuilder addObject(CharSequence name, Object value);

    @Override
    ResponseHeadersBuilder addObject(CharSequence name, Iterable<?> values);

    @Override
    ResponseHeadersBuilder addObject(CharSequence name, Object... values);

    @Override
    ResponseHeadersBuilder addObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    @Override
    ResponseHeadersBuilder addInt(CharSequence name, int value);

    @Override
    ResponseHeadersBuilder addLong(CharSequence name, long value);

    @Override
    ResponseHeadersBuilder addFloat(CharSequence name, float value);

    @Override
    ResponseHeadersBuilder addDouble(CharSequence name, double value);

    @Override
    ResponseHeadersBuilder addTimeMillis(CharSequence name, long value);

    @Override
    ResponseHeadersBuilder set(CharSequence name, String value);

    @Override
    ResponseHeadersBuilder set(CharSequence name, Iterable<String> values);

    @Override
    ResponseHeadersBuilder set(CharSequence name, String... values);

    @Override
    ResponseHeadersBuilder set(Iterable<? extends Entry<? extends CharSequence, String>> headers);

    @Override
    ResponseHeadersBuilder setIfAbsent(
            Iterable<? extends Entry<? extends CharSequence, String>> headers);

    @Override
    ResponseHeadersBuilder setObject(CharSequence name, Object value);

    @Override
    ResponseHeadersBuilder setObject(CharSequence name, Iterable<?> values);

    @Override
    ResponseHeadersBuilder setObject(CharSequence name, Object... values);

    @Override
    ResponseHeadersBuilder setObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    @Override
    ResponseHeadersBuilder setInt(CharSequence name, int value);

    @Override
    ResponseHeadersBuilder setLong(CharSequence name, long value);

    @Override
    ResponseHeadersBuilder setFloat(CharSequence name, float value);

    @Override
    ResponseHeadersBuilder setDouble(CharSequence name, double value);

    @Override
    ResponseHeadersBuilder setTimeMillis(CharSequence name, long value);

    @Override
    ResponseHeadersBuilder removeAndThen(CharSequence name);

    @Override
    ResponseHeadersBuilder clear();
}
