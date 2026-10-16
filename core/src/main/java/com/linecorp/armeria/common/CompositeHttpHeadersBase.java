/*
 * Copyright 2023 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.common.StringMultimap.DEFAULT_SIZE_HINT;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AsciiString;

/**
 * The composite container implementation which wraps {@link HttpHeadersBase}s
 * to avoid expensive copy operations.
 */
class CompositeHttpHeadersBase
        extends CompositeStringMultimap</* IN_NAME */ CharSequence, /* NAME */ AsciiString>
        implements HttpHeaderGetters {

    private static final Supplier<StringMultimap<CharSequence, AsciiString>> DEFAULT_APPENDER_SUPPLIER =
            () -> new HttpHeadersBase(DEFAULT_SIZE_HINT);

    CompositeHttpHeadersBase(HttpHeaderGetters... parents) {
        super(from(parents), DEFAULT_APPENDER_SUPPLIER);
    }

    CompositeHttpHeadersBase(@Nullable List<HttpHeaderGetters> additionals,
                             List<HttpHeaderGetters> parents,
                             @Nullable List<HttpHeaderGetters> defaults) {
        super(additionals != null ? from(additionals) : null,
              from(parents),
              defaults != null ? from(defaults) : null,
              DEFAULT_APPENDER_SUPPLIER);
    }

    private static List<StringMultimap<CharSequence, AsciiString>> from(HttpHeaderGetters... headers) {
        if (headers.length == 0) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<StringMultimap<CharSequence, AsciiString>> builder =
                ImmutableList.builder();
        for (HttpHeaderGetters header : headers) {
            if (header instanceof HttpHeadersBase) {
                builder.add((HttpHeadersBase) header);
            } else {
                builder.add(new HttpHeadersBase(header));
            }
        }

        return builder.build();
    }

    private static List<StringMultimap<CharSequence, AsciiString>> from(List<HttpHeaderGetters> headers) {
        if (headers.isEmpty()) {
            return ImmutableList.of();
        }

        final ImmutableList.Builder<StringMultimap<CharSequence, AsciiString>> builder =
                ImmutableList.builder();
        for (HttpHeaderGetters header : headers) {
            if (header instanceof HttpHeadersBase) {
                builder.add((HttpHeadersBase) header);
            } else {
                builder.add(new HttpHeadersBase(header));
            }
        }

        return builder.build();
    }

    final void contentLength(long contentLength) {
        checkArgument(contentLength >= 0, "contentLength: %s (expected: >= 0)", contentLength);
        remove0(HttpHeaderNames.CONTENT_LENGTH);
        ((HttpHeadersBase) appender()).contentLength(contentLength);
    }

    @Override
    public long contentLength() {
        return getLong(HttpHeaderNames.CONTENT_LENGTH, -1);
    }

    final void contentLengthUnknown() {
        remove0(HttpHeaderNames.CONTENT_LENGTH);
        ((HttpHeadersBase) appender()).contentLengthUnknown();
    }

    @Override
    public boolean isContentLengthUnknown() {
        return ((HttpHeaderGetters) appender()).isContentLengthUnknown();
    }

    final void contentType(MediaType contentType) {
        requireNonNull(contentType, "contentType");
        remove0(HttpHeaderNames.CONTENT_TYPE);
        ((HttpHeadersBase) appender()).contentType(contentType);
    }

    @Override
    public @Nullable MediaType contentType() {
        final String contentTypeString = get(HttpHeaderNames.CONTENT_TYPE);
        if (contentTypeString == null) {
            return null;
        }

        try {
            return MediaType.parse(contentTypeString);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    final void contentDisposition(ContentDisposition contentDisposition) {
        requireNonNull(contentDisposition, "contentDisposition");
        remove0(HttpHeaderNames.CONTENT_DISPOSITION);
        ((HttpHeadersBase) appender()).contentDisposition(contentDisposition);
    }

    @Override
    public @Nullable ContentDisposition contentDisposition() {
        final String contentDispositionString = get(HttpHeaderNames.CONTENT_DISPOSITION);
        if (contentDispositionString == null) {
            return null;
        }

        try {
            return ContentDisposition.parse(contentDispositionString);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    final void endOfStream(boolean endOfStream) {
        ((HttpHeadersBase) appender()).endOfStream(endOfStream);
    }

    @Override
    public boolean isEndOfStream() {
        for (StringMultimapGetters<CharSequence, AsciiString> delegate : delegates()) {
            if (((HttpHeaderGetters) delegate).isEndOfStream()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public final int hashCode() {
        final int hashCode = super.hashCode();
        return isEndOfStream() ? ~hashCode : hashCode;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof HttpHeaderGetters)) {
            return false;
        }

        // `contentLengthUnknown` is excluded from the comparison since it is not a field expressing headers
        // data.
        return isEndOfStream() == ((HttpHeaderGetters) o).isEndOfStream() && super.equals(o);
    }

    @Override
    public final String toString() {
        final int size = size();
        final boolean isEndOfStream = isEndOfStream();
        if (size == 0) {
            return isEndOfStream ? "[EOS]" : "[]";
        }

        final StringBuilder sb = new StringBuilder(7 + size * 20);
        if (isEndOfStream) {
            sb.append("[EOS, ");
        } else {
            sb.append('[');
        }

        for (Map.Entry<AsciiString, String> e : this) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(", ");
        }

        final int length = sb.length();
        sb.setCharAt(length - 2, ']');
        return sb.substring(0, length - 1);
    }
}
