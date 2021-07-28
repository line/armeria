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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isAbsoluteUri;
import static java.util.Comparator.comparingDouble;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Locale.LanguageRange;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.math.IntMath;

import com.linecorp.armeria.internal.common.util.StringUtil;

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

    private final Map<AsciiString, Object> cache;

    private boolean endOfStream;

    HttpHeadersBase(int sizeHint) {
        super(sizeHint);
        cache = new HashMap<>(4);
    }

    /**
     * Creates a shallow or deep copy of the specified {@link HttpHeadersBase}.
     */
    HttpHeadersBase(HttpHeadersBase parent, boolean shallowCopy) {
        super(parent, shallowCopy);
        endOfStream = parent.endOfStream;
        cache = new HashMap<>(parent.cache);
    }

    /**
     * Creates a deep copy of the specified {@link HttpHeaderGetters}.
     */
    HttpHeadersBase(HttpHeaderGetters parent) {
        super(parent);
        assert !(parent instanceof HttpHeadersBase);
        endOfStream = parent.isEndOfStream();
        cache = new HashMap<>(4);
    }

    @Override
    void onChange(@Nullable AsciiString name) {
        // This method could be called before the 'cache' field is initialized.
        if (cache == null || cache.isEmpty()) {
            return;
        }

        cache.remove(name);
    }

    @Override
    void onClear() {
        if (cache == null || cache.isEmpty()) {
            return;
        }
        // Invalidate all cached values
        cache.clear();
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

    /**
     * Adds the specified {@code cookies} with {@link HttpHeaderNames#COOKIE}.
     */
    final void cookie(Iterable<? extends Cookie> cookies) {
        //noinspection unchecked
        addCookies((Iterable<Cookie>) cookies, HttpHeaderNames.COOKIE, Cookie::toCookieHeader);
    }

    Cookies cookie() {
        return getCookie(HttpHeaderNames.COOKIE, Cookie::fromCookieHeaders);
    }

    /**
     * Adds the specified {@code setCookies} with {@link HttpHeaderNames#SET_COOKIE}.
     */
    final void setCookie(Iterable<? extends Cookie> setCookie) {
        //noinspection unchecked
        addCookies((Iterable<Cookie>) setCookie, HttpHeaderNames.SET_COOKIE, Cookie::toSetCookieHeaders);
    }

    Cookies setCookie() {
        return getCookie(HttpHeaderNames.SET_COOKIE, Cookie::fromSetCookieHeaders);
    }

    private Cookies getCookie(AsciiString cookieHeaderName, Function<List<String>, Cookies> cookiesParser) {
        @SuppressWarnings("unchecked")
        final Iterable<Cookie> cookies = (Iterable<Cookie>) cache.get(cookieHeaderName);
        if (cookies == null) {
            // Cache miss. Check the container values.
            final List<String> cookiesString = getAll(cookieHeaderName);
            if (cookiesString.isEmpty()) {
                final Cookies emptyCookies = Cookies.of();
                cache.put(cookieHeaderName, emptyCookies);
                return emptyCookies;
            } else {
                final Cookies parsedCookies = cookiesParser.apply(cookiesString);
                cache.put(cookieHeaderName, parsedCookies);
                return parsedCookies;
            }
        }

        if (cookies instanceof Cookies) {
            return (Cookies) cookies;
        } else {
            final Cookies immutableCookies = Cookies.of(cookies);
            // Make the cached cookies immutable.
            cache.put(cookieHeaderName, immutableCookies);
            return immutableCookies;
        }
    }

    private void addCookies(Iterable<Cookie> newCookies, AsciiString cookieHeaderName,
                            Function<Iterable<? extends Cookie>, Object> toCookiesString) {
        @SuppressWarnings("unchecked")
        Iterable<Cookie> cachedCookies = (Iterable<Cookie>) cache.get(cookieHeaderName);
        if (cachedCookies == null) {
            if (newCookies instanceof Cookies) {
                cachedCookies = newCookies;
            } else {
                final ArrayList<Cookie> copied = new ArrayList<>(4);
                for (Cookie cookie : newCookies) {
                    copied.add(cookie);
                }
                cachedCookies = copied;
            }
        } else {
            // Append 'newCookies' to 'cachedCookies'
            if (cachedCookies instanceof Cookies) {
                // Make cached Cookies mutable
                cachedCookies = new ArrayList<>((Collection<Cookie>) cachedCookies);
            }
            assert cachedCookies instanceof ArrayList;
            final ArrayList<Cookie> cookieList = (ArrayList<Cookie>) cachedCookies;
            for (Cookie cookie : newCookies) {
                if (!cookieList.contains(cookie)) {
                    // Add only a non-duplicate Cookie.
                    cookieList.add(cookie);
                }
            }
        }
        // Cache mutable cookies for efficiency.
        // The mutable cookies will be changed into (immutable) Cookies when cookie() is called.
        cache.put(cookieHeaderName, cachedCookies);

        if (HttpHeaderNames.COOKIE.equals(cookieHeaderName)) {
            // Stringify all cookies
            final Object cookiesString = toCookiesString.apply(cachedCookies);
            assert cookiesString instanceof String;
            assert cookieHeaderName.equals(HttpHeaderNames.COOKIE);
            setOnly(cookieHeaderName, (String) cookiesString);
        } else if (HttpHeaderNames.SET_COOKIE.equals(cookieHeaderName)) {
            // Only stringify new cookies
            final Object cookiesString = toCookiesString.apply(newCookies);
            assert cookiesString instanceof Iterable;
            //noinspection unchecked
            addOnly(cookieHeaderName, (Iterable<String>) cookiesString);
        } else {
            throw new Error(); // Should never reach here.
        }
    }

    final void acceptLanguages(Iterable<LanguageRange> acceptLanguages) {
        final StringJoiner joiner = new StringJoiner(", ");
        for (LanguageRange range : acceptLanguages) {
            if (range.getWeight() == 1.0d) {
                joiner.add(range.getRange());
            } else {
                joiner.add(range.getRange() + ";q=" + range.getWeight());
            }
        }
        set(HttpHeaderNames.ACCEPT_LANGUAGE, joiner.toString());
    }

    @Nullable
    List<LanguageRange> acceptLanguages() {
        final List<String> acceptHeaders = getAll(HttpHeaderNames.ACCEPT_LANGUAGE);
        if (acceptHeaders.isEmpty()) {
            // TODO(ikhoon): Return an empty list if no accept-language exists in Armeria 2.0
            return null;
        }

        try {
            final List<LanguageRange> acceptLanguages = new ArrayList<>(4);
            for (String acceptHeader : acceptHeaders) {
                acceptLanguages.addAll(LanguageRange.parse(acceptHeader));
            }
            acceptLanguages.sort(comparingDouble(LanguageRange::getWeight).reversed());

            return Collections.unmodifiableList(acceptLanguages);
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
        final HttpMethod method = (HttpMethod) cache.get(HttpHeaderNames.METHOD);
        if (method != null) {
            return method;
        }

        final String methodStr = get(HttpHeaderNames.METHOD);
        checkState(methodStr != null, ":method header does not exist.");
        final HttpMethod parsed = HttpMethod.isSupported(methodStr) ? HttpMethod.valueOf(methodStr)
                                                                    : HttpMethod.UNKNOWN;
        cache.put(HttpHeaderNames.METHOD, parsed);
        return parsed;
    }

    final void method(HttpMethod method) {
        requireNonNull(method, "method");
        cache.put(HttpHeaderNames.METHOD, method);
        setOnly(HttpHeaderNames.METHOD, method.name());
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
        final HttpStatus status = (HttpStatus) cache.get(HttpHeaderNames.STATUS);
        if (status != null) {
            return status;
        }

        final String statusStr = get(HttpHeaderNames.STATUS);
        checkState(statusStr != null, ":status header does not exist.");
        final HttpStatus parsed = HttpStatus.valueOf(statusStr);
        cache.put(HttpHeaderNames.STATUS, parsed);
        return parsed;
    }

    final void status(int statusCode) {
        status(HttpStatus.valueOf(statusCode));
    }

    final void status(HttpStatus status) {
        requireNonNull(status, "status");
        cache.put(HttpHeaderNames.STATUS, status);
        setOnly(HttpHeaderNames.STATUS, status.codeAsText());
    }

    final void contentLength(long contentLength) {
        checkArgument(contentLength >= 0, "contentLength: %s (expected: >= 0)", contentLength);
        cache.put(HttpHeaderNames.CONTENT_LENGTH, contentLength);
        final String contentLengthString = StringUtil.toString(contentLength);
        setOnly(HttpHeaderNames.CONTENT_LENGTH, contentLengthString);
    }

    @Override
    public long contentLength() {
        final Long contentLength = (Long) cache.get(HttpHeaderNames.CONTENT_LENGTH);
        if (contentLength != null) {
            return contentLength;
        }

        final String contentLengthString = get(HttpHeaderNames.CONTENT_LENGTH);
        if (contentLengthString != null) {
            final long parsed = Long.parseLong(contentLengthString);
            cache.put(HttpHeaderNames.CONTENT_LENGTH, parsed);
            return parsed;
        } else {
            cache.put(HttpHeaderNames.CONTENT_LENGTH, -1);
            return -1;
        }
    }

    @Nullable
    @Override
    public MediaType contentType() {
        final MediaType contentType = (MediaType) cache.get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType != null) {
            return contentType;
        }

        final String contentTypeString = get(HttpHeaderNames.CONTENT_TYPE);
        if (contentTypeString == null) {
            return null;
        }

        try {
            final MediaType parsed = MediaType.parse(contentTypeString);
            cache.put(HttpHeaderNames.CONTENT_TYPE, parsed);
            return parsed;
        } catch (IllegalArgumentException unused) {
            // Invalid media type
            return null;
        }
    }

    final void contentType(MediaType contentType) {
        requireNonNull(contentType, "contentType");
        cache.put(HttpHeaderNames.CONTENT_TYPE, contentType);
        setOnly(HttpHeaderNames.CONTENT_TYPE, contentType.toString());
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
