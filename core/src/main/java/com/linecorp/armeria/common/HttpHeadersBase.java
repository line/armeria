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
/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isAbsoluteUri;
import static java.util.Comparator.comparingDouble;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.math.IntMath;

import io.netty.util.AsciiString;

/**
 * The base container implementation of {@link HttpHeaders} and {@link HttpHeadersBuilder}.
 */
class HttpHeadersBase
        extends StringMultimap</* IN_NAME */ CharSequence, /* NAME */ AsciiString>
        implements HttpHeaderGetters {

    private static final BitSet PROHIBITED_VALUE_CHARS;
    private static final String[] PROHIBITED_VALUE_CHAR_NAMES;
    private static final char LAST_PROHIBITED_VALUE_CHAR;

    static {
        PROHIBITED_VALUE_CHARS = new BitSet();
        PROHIBITED_VALUE_CHARS.set(0);
        PROHIBITED_VALUE_CHARS.set('\n');
        PROHIBITED_VALUE_CHARS.set(0xB);
        PROHIBITED_VALUE_CHARS.set('\f');
        PROHIBITED_VALUE_CHARS.set('\r');
        LAST_PROHIBITED_VALUE_CHAR = (char) (PROHIBITED_VALUE_CHARS.size() - 1);

        PROHIBITED_VALUE_CHAR_NAMES = new String[PROHIBITED_VALUE_CHARS.size()];
        PROHIBITED_VALUE_CHAR_NAMES[0] = "<NUL>";
        PROHIBITED_VALUE_CHAR_NAMES['\n'] = "<LF>";
        PROHIBITED_VALUE_CHAR_NAMES[0xB] = "<VT>";
        PROHIBITED_VALUE_CHAR_NAMES['\f'] = "<FF>";
        PROHIBITED_VALUE_CHAR_NAMES['\r'] = "<CR>";
    }

    private boolean endOfStream;

    HttpHeadersBase(int sizeHint) {
        super(sizeHint);
    }

    /**
     * Creates a shallow or deep copy of the specified {@link HttpHeadersBase}.
     */
    HttpHeadersBase(HttpHeadersBase parent, boolean shallowCopy) {
        super(parent, shallowCopy);
        endOfStream = parent.endOfStream;
    }

    /**
     * Creates a deep copy of the specified {@link HttpHeaderGetters}.
     */
    HttpHeadersBase(HttpHeaderGetters parent) {
        super(parent);
        assert !(parent instanceof HttpHeadersBase);
        endOfStream = parent.isEndOfStream();
    }

    @Override
    final int hashName(CharSequence name) {
        return AsciiString.hashCode(name);
    }

    @Override
    final boolean nameEquals(AsciiString a, CharSequence b) {
        return a.contentEqualsIgnoreCase(b);
    }

    @Override
    final AsciiString normalizeName(CharSequence name) {
        return HttpHeaderNames.of(name);
    }

    @Override
    final boolean isFirstGroup(AsciiString name) {
        // Pseudo headers must come first during iteration.
        return !name.isEmpty() && name.byteAt(0) == ':';
    }

    @Override
    final void validateValue(String value) {
        if (!Flags.validateHeaders()) {
            return;
        }

        final int valueLength = value.length();
        for (int i = 0; i < valueLength; i++) {
            final char ch = value.charAt(i);
            if (ch > LAST_PROHIBITED_VALUE_CHAR) {
                continue;
            }

            if (PROHIBITED_VALUE_CHARS.get(ch)) {
                throw new IllegalArgumentException(malformedHeaderValueMessage(value));
            }
        }
    }

    private static String malformedHeaderValueMessage(String value) {
        final StringBuilder buf = new StringBuilder(IntMath.saturatedAdd(value.length(), 64));
        buf.append("malformed header value: ");

        final int valueLength = value.length();
        for (int i = 0; i < valueLength; i++) {
            final char ch = value.charAt(i);
            if (PROHIBITED_VALUE_CHARS.get(ch)) {
                buf.append(PROHIBITED_VALUE_CHAR_NAMES[ch]);
            } else {
                buf.append(ch);
            }
        }

        return buf.toString();
    }

    // Shortcut methods

    URI uri() {
        final String uri;
        final String path = path();
        if (isAbsoluteUri(path)) {
            uri = path;
        } else {
            final String scheme = scheme();
            checkState(scheme != null, ":scheme header does not exist.");
            final String authority = authority();
            checkState(authority != null, ":authority header does not exist.");
            uri = scheme + "://" + authority + path;
        }

        try {
            return new URI(uri);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("not a valid URI: " + uri, e);
        }
    }

    Cookies cookie() {
        final List<String> cookieStrings = getAll(HttpHeaderNames.COOKIE);
        if (cookieStrings.isEmpty()) {
            return Cookies.of();
        }
        return Cookie.fromCookieHeaders(cookieStrings);
    }

    Cookies setCookie() {
        final List<String> cookieHeaders = getAll(HttpHeaderNames.SET_COOKIE);
        return Cookie.fromSetCookieHeaders(cookieHeaders);
    }

    @Nullable
    List<LanguageRange> acceptLanguages() {
        final List<String> acceptHeaders = getAll(HttpHeaderNames.ACCEPT_LANGUAGE);
        if (acceptHeaders.isEmpty()) {
            return null;
        }

        try {
            final List<LanguageRange> acceptLanguages = new ArrayList<>(4);
            for (String acceptHeader : acceptHeaders) {
                acceptLanguages.addAll(LanguageRange.parse(acceptHeader));
            }
            acceptLanguages.sort(comparingDouble(LanguageRange::getWeight).reversed());

            return ImmutableList.copyOf(acceptLanguages);
        } catch (IllegalArgumentException e) {
            // If any port of any of the headers is ill-formed
            return null;
        }
    }

    @Nullable
    Locale selectLocale(Iterable<Locale> supportedLocales) {
        requireNonNull(supportedLocales, "supportedLocales");
        final Collection<Locale> localeCollection;
        if (supportedLocales instanceof Collection) {
            localeCollection = (Collection<Locale>) supportedLocales;
        } else {
            localeCollection = ImmutableList.copyOf(supportedLocales);
        }
        if (localeCollection.isEmpty()) {
            return null;
        }
        final List<LanguageRange> languageRanges = acceptLanguages();
        if (languageRanges == null) {
            return null;
        }
        return languageRanges
                .stream()
                .flatMap(it -> Locale.filter(ImmutableList.of(it), localeCollection).stream())
                .findFirst()
                .orElse(null);
    }

    HttpMethod method() {
        final String methodStr = get(HttpHeaderNames.METHOD);
        checkState(methodStr != null, ":method header does not exist.");
        return HttpMethod.isSupported(methodStr) ? HttpMethod.valueOf(methodStr)
                                                 : HttpMethod.UNKNOWN;
    }

    final void method(HttpMethod method) {
        requireNonNull(method, "method");
        set(HttpHeaderNames.METHOD, method.name());
    }

    @Nullable
    String scheme() {
        return get(HttpHeaderNames.SCHEME);
    }

    final void scheme(String scheme) {
        requireNonNull(scheme, "scheme");
        set(HttpHeaderNames.SCHEME, scheme);
    }

    @Nullable
    String authority() {
        final String authority = get(HttpHeaderNames.AUTHORITY);
        return authority != null ? authority : get(HttpHeaderNames.HOST);
    }

    final void authority(String authority) {
        requireNonNull(authority, "authority");
        set(HttpHeaderNames.AUTHORITY, authority);
    }

    String path() {
        final String path = get(HttpHeaderNames.PATH);
        checkState(path != null, ":path header does not exist.");
        return path;
    }

    final void path(String path) {
        requireNonNull(path, "path");
        set(HttpHeaderNames.PATH, path);
    }

    HttpStatus status() {
        final String statusStr = get(HttpHeaderNames.STATUS);
        checkState(statusStr != null, ":status header does not exist.");
        return HttpStatus.valueOf(statusStr);
    }

    final void status(int statusCode) {
        status(HttpStatus.valueOf(statusCode));
    }

    final void status(HttpStatus status) {
        requireNonNull(status, "status");
        set(HttpHeaderNames.STATUS, status.codeAsText());
    }

    @Nullable
    @Override
    public MediaType contentType() {
        final String contentTypeString = get(HttpHeaderNames.CONTENT_TYPE);
        if (contentTypeString == null) {
            return null;
        }

        try {
            return MediaType.parse(contentTypeString);
        } catch (IllegalArgumentException unused) {
            // Invalid media type
            return null;
        }
    }

    final void contentType(MediaType contentType) {
        requireNonNull(contentType, "contentType");
        set(HttpHeaderNames.CONTENT_TYPE, contentType.toString());
    }

    @Override
    @Nullable
    public ContentDisposition contentDisposition() {
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

    final void contentDisposition(ContentDisposition contentDisposition) {
        requireNonNull(contentDisposition, "contentDisposition");
        set(HttpHeaderNames.CONTENT_DISPOSITION, contentDisposition.asHeaderValue());
    }

    // Getters

    @Override
    public final boolean isEndOfStream() {
        return endOfStream;
    }

    // Mutators

    final void endOfStream(boolean endOfStream) {
        this.endOfStream = endOfStream;
    }

    @Override
    public final int hashCode() {
        final int hashCode = super.hashCode();
        return endOfStream ? ~hashCode : hashCode;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof HttpHeaderGetters)) {
            return false;
        }

        return endOfStream == ((HttpHeaderGetters) o).isEndOfStream() && super.equals(o);
    }

    @Override
    public final String toString() {
        if (size == 0) {
            return endOfStream ? "[EOS]" : "[]";
        }

        final StringBuilder sb = new StringBuilder(7 + size * 20);
        if (endOfStream) {
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
