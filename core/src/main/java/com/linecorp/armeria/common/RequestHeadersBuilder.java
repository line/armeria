package com.linecorp.armeria.common;

import java.util.Map.Entry;

/**
 * Builds a {@link RequestHeaders}.
 *
 * @see RequestHeaders#builder()
 * @see RequestHeaders#toBuilder()
 */
public interface RequestHeadersBuilder extends HttpHeadersBuilder, RequestHeaderGetters {
    /**
     * Returns a newly created {@link RequestHeaders} with the entries in this builder.
     * Note that any further modification of this builder is prohibited after this method is invoked.
     *
     * @throws IllegalStateException if this builder does not have {@code ":method"} and
     *                               {@code ":path"} headers set.
     */
    @Override
    RequestHeaders build();

    /**
     * Sets the {@code ":method"} header.
     */
    RequestHeadersBuilder method(HttpMethod method);

    /**
     * Sets the {@code ":path"} headers.
     */
    RequestHeadersBuilder path(String path);

    /**
     * Sets the {@code ":scheme"} headers.
     */
    RequestHeadersBuilder scheme(String scheme);

    /**
     * Sets the {@code ":authority"} headers.
     */
    RequestHeadersBuilder authority(String authority);

    // Override the return type of the chaining methods in the superclass.

    @Override
    RequestHeadersBuilder sizeHint(int sizeHint);

    @Override
    RequestHeadersBuilder endOfStream(boolean endOfStream);

    @Override
    RequestHeadersBuilder contentType(MediaType contentType);

    @Override
    RequestHeadersBuilder add(CharSequence name, String value);

    @Override
    RequestHeadersBuilder add(CharSequence name, Iterable<String> values);

    @Override
    RequestHeadersBuilder add(CharSequence name, String... values);

    @Override
    RequestHeadersBuilder add(Iterable<? extends Entry<? extends CharSequence, String>> headers);

    @Override
    RequestHeadersBuilder addObject(CharSequence name, Object value);

    @Override
    RequestHeadersBuilder addObject(CharSequence name, Iterable<?> values);

    @Override
    RequestHeadersBuilder addObject(CharSequence name, Object... values);

    @Override
    RequestHeadersBuilder addObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    @Override
    RequestHeadersBuilder addInt(CharSequence name, int value);

    @Override
    RequestHeadersBuilder addLong(CharSequence name, long value);

    @Override
    RequestHeadersBuilder addFloat(CharSequence name, float value);

    @Override
    RequestHeadersBuilder addDouble(CharSequence name, double value);

    @Override
    RequestHeadersBuilder addTimeMillis(CharSequence name, long value);

    @Override
    RequestHeadersBuilder set(CharSequence name, String value);

    @Override
    RequestHeadersBuilder set(CharSequence name, Iterable<String> values);

    @Override
    RequestHeadersBuilder set(CharSequence name, String... values);

    @Override
    RequestHeadersBuilder set(Iterable<? extends Entry<? extends CharSequence, String>> headers);

    @Override
    RequestHeadersBuilder setIfAbsent(
            Iterable<? extends Entry<? extends CharSequence, String>> headers);

    @Override
    RequestHeadersBuilder setObject(CharSequence name, Object value);

    @Override
    RequestHeadersBuilder setObject(CharSequence name, Iterable<?> values);

    @Override
    RequestHeadersBuilder setObject(CharSequence name, Object... values);

    @Override
    RequestHeadersBuilder setObject(Iterable<? extends Entry<? extends CharSequence, ?>> headers);

    @Override
    RequestHeadersBuilder setInt(CharSequence name, int value);

    @Override
    RequestHeadersBuilder setLong(CharSequence name, long value);

    @Override
    RequestHeadersBuilder setFloat(CharSequence name, float value);

    @Override
    RequestHeadersBuilder setDouble(CharSequence name, double value);

    @Override
    RequestHeadersBuilder setTimeMillis(CharSequence name, long value);

    @Override
    RequestHeadersBuilder removeAndThen(CharSequence name);

    @Override
    RequestHeadersBuilder clear();
}
