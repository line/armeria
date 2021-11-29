/*
 * Copyright 2016 LINE Corporation
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
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal.common;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.netty.util.AsciiString.EMPTY_STRING;
import static io.netty.util.ByteProcessor.FIND_COMMA;
import static io.netty.util.internal.StringUtil.decodeHexNibble;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BiConsumer;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Http1HeaderNaming;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Version;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;
import com.linecorp.armeria.server.ServerConfig;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DefaultHeaders;
import io.netty.handler.codec.UnsupportedValueConverter;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;
import io.netty.util.AsciiString;
import io.netty.util.HashingStrategy;
import io.netty.util.internal.StringUtil;

/**
 * Provides various utility functions for internal use related with HTTP.
 *
 * <p>The conversion between HTTP/1 and HTTP/2 has been forked from Netty's {@link HttpConversionUtil}.
 */
public final class ArmeriaHttpUtil {

    // Forked from Netty 4.1.34 at 4921f62c8ab8205fd222439dcd1811760b05daf1

    /**
     * The default case-insensitive {@link AsciiString} hasher and comparator for HTTP/2 headers.
     */
    private static final HashingStrategy<AsciiString> HTTP2_HEADER_NAME_HASHER =
            new HashingStrategy<AsciiString>() {
                @Override
                public int hashCode(AsciiString o) {
                    return o.hashCode();
                }

                @Override
                public boolean equals(AsciiString a, AsciiString b) {
                    return a.contentEqualsIgnoreCase(b);
                }
            };

    /**
     * The default HTTP content-type charset.
     *
     * <p>Note that we use {@link StandardCharsets#UTF_8} as default because it is common practice even though
     * it's not the HTTP standard.
     */
    public static final Charset HTTP_DEFAULT_CONTENT_CHARSET = StandardCharsets.UTF_8;

    /**
     * The old {@code "proxy-connection"} header which has been superceded by {@code "connection"}.
     */
    public static final AsciiString HEADER_NAME_PROXY_CONNECTION = AsciiString.cached("proxy-connection");

    /**
     * The set of headers that should not be directly copied when converting headers from HTTP/1 to HTTP/2.
     */
    private static final CaseInsensitiveMap HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST = new CaseInsensitiveMap();

    /**
     * The set of headers that should not be directly copied when converting headers from HTTP/2 to HTTP/1.
     */
    private static final CaseInsensitiveMap HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST = new CaseInsensitiveMap();

    /**
     * The set of headers that must not be directly copied when converting trailers.
     */
    private static final CaseInsensitiveMap HTTP_TRAILER_DISALLOWED_LIST = new CaseInsensitiveMap();

    static {
        HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST.add(HttpHeaderNames.CONNECTION, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST.add(HttpHeaderNames.KEEP_ALIVE, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST.add(HEADER_NAME_PROXY_CONNECTION, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST.add(HttpHeaderNames.TRANSFER_ENCODING, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST.add(HttpHeaderNames.UPGRADE, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST.add(ExtensionHeaderNames.STREAM_ID.text(), EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST.add(ExtensionHeaderNames.SCHEME.text(), EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST.add(ExtensionHeaderNames.PATH.text(), EMPTY_STRING);

        // https://datatracker.ietf.org/doc/html/rfc7540#section-8.1.2.3
        HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST.add(HttpHeaderNames.AUTHORITY, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST.add(HttpHeaderNames.METHOD, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST.add(HttpHeaderNames.PATH, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST.add(HttpHeaderNames.SCHEME, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST.add(HttpHeaderNames.STATUS, EMPTY_STRING);

        // https://datatracker.ietf.org/doc/html/rfc7540#section-8.1
        // The "chunked" transfer encoding defined in Section 4.1 of [RFC7230] MUST NOT be used in HTTP/2.
        HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST.add(HttpHeaderNames.TRANSFER_ENCODING, EMPTY_STRING);

        HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST.add(ExtensionHeaderNames.STREAM_ID.text(), EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST.add(ExtensionHeaderNames.SCHEME.text(), EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST.add(ExtensionHeaderNames.PATH.text(), EMPTY_STRING);

        // https://datatracker.ietf.org/doc/html/rfc7230#section-4.1.2
        // https://datatracker.ietf.org/doc/html/rfc7540#section-8.1
        // A sender MUST NOT generate a trailer that contains a field necessary for message framing:
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.TRANSFER_ENCODING, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.CONTENT_LENGTH, EMPTY_STRING);

        // for request modifiers:
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.CACHE_CONTROL, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.EXPECT, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.HOST, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.MAX_FORWARDS, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.PRAGMA, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.RANGE, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.TE, EMPTY_STRING);

        // for authentication:
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.WWW_AUTHENTICATE, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.AUTHORIZATION, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.PROXY_AUTHENTICATE, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.PROXY_AUTHORIZATION, EMPTY_STRING);

        // for response control data:
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.DATE, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.LOCATION, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.RETRY_AFTER, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.VARY, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.WARNING, EMPTY_STRING);

        // or for determining how to process the payload:
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.CONTENT_ENCODING, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.CONTENT_TYPE, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.CONTENT_RANGE, EMPTY_STRING);
        HTTP_TRAILER_DISALLOWED_LIST.add(HttpHeaderNames.TRAILER, EMPTY_STRING);
    }

    static final Set<AsciiString> ADDITIONAL_REQUEST_HEADER_DISALLOWED_LIST = ImmutableSet.of(
            HttpHeaderNames.SCHEME, HttpHeaderNames.STATUS, HttpHeaderNames.METHOD, HttpHeaderNames.AUTHORITY);

    private static final Set<AsciiString> REQUEST_PSEUDO_HEADERS = ImmutableSet.of(
            HttpHeaderNames.METHOD, HttpHeaderNames.SCHEME, HttpHeaderNames.AUTHORITY,
            HttpHeaderNames.PATH, HttpHeaderNames.PROTOCOL);

    private static final Set<AsciiString> PSEUDO_HEADERS = ImmutableSet.<AsciiString>builder()
                                                                       .addAll(REQUEST_PSEUDO_HEADERS)
                                                                       .add(HttpHeaderNames.STATUS)
                                                                       .build();

    public static final String SERVER_HEADER =
            "Armeria/" + Version.get("armeria", ArmeriaHttpUtil.class.getClassLoader())
                                .artifactVersion();

    /**
     * Translations from HTTP/2 header name to the HTTP/1.x equivalent. Currently, we expect these headers to
     * only allow a single value in the request. If adding headers that can potentially have multiple values,
     * please check the usage in code accordingly.
     */
    private static final CaseInsensitiveMap REQUEST_HEADER_TRANSLATIONS = new CaseInsensitiveMap();

    static {
        REQUEST_HEADER_TRANSLATIONS.add(Http2Headers.PseudoHeaderName.AUTHORITY.value(),
                                        HttpHeaderNames.HOST);
    }

    /**
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-8.1.2.3">rfc7540, 8.1.2.3</a> states the path must not
     * be empty, and instead should be {@code /}.
     */
    private static final String EMPTY_REQUEST_PATH = "/";

    private static final Splitter COOKIE_SPLITTER = Splitter.on(';').trimResults().omitEmptyStrings();
    private static final String COOKIE_SEPARATOR = "; ";
    private static final Joiner COOKIE_JOINER = Joiner.on(COOKIE_SEPARATOR);

    @Nullable
    private static final LoadingCache<AsciiString, String> HEADER_VALUE_CACHE =
            Flags.headerValueCacheSpec() != null ? buildCache(Flags.headerValueCacheSpec()) : null;
    private static final Set<AsciiString> CACHED_HEADERS = Flags.cachedHeaders().stream().map(AsciiString::of)
                                                                .collect(toImmutableSet());

    private static LoadingCache<AsciiString, String> buildCache(String spec) {
        return Caffeine.from(spec).build(AsciiString::toString);
    }

    /**
     * Concatenates two path strings.
     */
    public static String concatPaths(@Nullable String path1, @Nullable String path2) {
        path2 = path2 == null ? "" : path2;

        if (path1 == null || path1.isEmpty() || EMPTY_REQUEST_PATH.equals(path1)) {
            if (path2.isEmpty()) {
                return EMPTY_REQUEST_PATH;
            }

            if (path2.charAt(0) == '/') {
                return path2; // Most requests will land here.
            }

            return '/' + path2;
        }

        // At this point, we are sure path1 is neither empty nor null.
        if (path2.isEmpty()) {
            // Only path1 is non-empty. No need to concatenate.
            return path1;
        }

        if (path1.charAt(path1.length() - 1) == '/') {
            if (path2.charAt(0) == '/') {
                // path1 ends with '/' and path2 starts with '/'.
                // Avoid double-slash by stripping the first slash of path2.
                return new StringBuilder(path1.length() + path2.length() - 1)
                        .append(path1).append(path2, 1, path2.length()).toString();
            }

            // path1 ends with '/' and path2 does not start with '/'.
            // Simple concatenation would suffice.
            return path1 + path2;
        }

        if (path2.charAt(0) == '/' || path2.charAt(0) == '?') {
            // path1 does not end with '/' and path2 starts with '/' or '?'
            // Simple concatenation would suffice.
            return path1 + path2;
        }

        // path1 does not end with '/' and path2 does not start with '/' or '?'.
        // Need to insert '/' between path1 and path2.
        return path1 + '/' + path2;
    }

    /**
     * Decodes a percent-encoded path string.
     */
    public static String decodePath(String path) {
        if (path.indexOf('%') < 0) {
            // No need to decoded; not percent-encoded
            return path;
        }

        // Decode percent-encoded characters.
        // An invalid character is replaced with 0xFF, which will be replaced into '�' by UTF-8 decoder.
        final int len = path.length();
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final byte[] buf = tempThreadLocals.byteArray(len);
            int dstLen = 0;
            for (int i = 0; i < len; i++) {
                final char ch = path.charAt(i);
                if (ch != '%') {
                    buf[dstLen++] = (byte) ((ch & 0xFF80) == 0 ? ch : 0xFF);
                    continue;
                }

                // Decode a percent-encoded character.
                final int hexEnd = i + 3;
                if (hexEnd > len) {
                    // '%' or '%x' (must be followed by two hexadigits)
                    buf[dstLen++] = (byte) 0xFF;
                    break;
                }

                final int digit1 = decodeHexNibble(path.charAt(++i));
                final int digit2 = decodeHexNibble(path.charAt(++i));
                if (digit1 < 0 || digit2 < 0) {
                    // The first or second digit is not hexadecimal.
                    buf[dstLen++] = (byte) 0xFF;
                } else {
                    buf[dstLen++] = (byte) ((digit1 << 4) | digit2);
                }
            }

            return new String(buf, 0, dstLen, StandardCharsets.UTF_8);
        }
    }

    /**
     * Returns {@code true} if the specified {@code path} is an absolute {@code URI}.
     */
    public static boolean isAbsoluteUri(@Nullable String maybeUri) {
        if (maybeUri == null) {
            return false;
        }
        final int firstColonIdx = maybeUri.indexOf(':');
        if (firstColonIdx <= 0 || firstColonIdx + 3 >= maybeUri.length()) {
            return false;
        }
        final int firstSlashIdx = maybeUri.indexOf('/');
        if (firstSlashIdx <= 0 || firstSlashIdx < firstColonIdx) {
            return false;
        }

        return maybeUri.charAt(firstColonIdx + 1) == '/' && maybeUri.charAt(firstColonIdx + 2) == '/';
    }

    /**
     * Returns {@code true} if the specified HTTP status string represents an informational status.
     */
    public static boolean isInformational(@Nullable String statusText) {
        return statusText != null && !statusText.isEmpty() && statusText.charAt(0) == '1';
    }

    /**
     * Returns {@code true} if the content of the response with the given {@link HttpStatus} is one of
     * {@link HttpStatus#NO_CONTENT}, {@link HttpStatus#RESET_CONTENT} and {@link HttpStatus#NOT_MODIFIED}.
     *
     * @throws IllegalArgumentException if the specified {@code content} is not empty when the specified
     *                                  {@link HttpStatus} is one of {@link HttpStatus#NO_CONTENT},
     *                                  {@link HttpStatus#RESET_CONTENT} and {@link HttpStatus#NOT_MODIFIED}.
     */
    public static boolean isContentAlwaysEmptyWithValidation(HttpStatus status, HttpData content) {
        if (!status.isContentAlwaysEmpty()) {
            return false;
        }

        if (!content.isEmpty()) {
            throw new IllegalArgumentException(
                    "A " + status + " response must have empty content: " + content.length() + " byte(s)");
        }

        return true;
    }

    /**
     * Returns {@code true} if the specified {@code headers} is a CORS preflight request.
     */
    public static boolean isCorsPreflightRequest(RequestHeaders headers) {
        requireNonNull(headers, "headers");
        return headers.method() == HttpMethod.OPTIONS &&
               headers.contains(HttpHeaderNames.ORIGIN) &&
               headers.contains(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD);
    }

    /**
     * Returns the disallowed response headers.
     */
    @VisibleForTesting
    static Set<AsciiString> disallowedResponseHeaderNames() {
        // Request Pseudo-Headers are not allowed for response headers.
        // https://datatracker.ietf.org/doc/html/rfc7540#section-8.1.2.3
        return REQUEST_PSEUDO_HEADERS;
    }

    /**
     * Parses the specified HTTP header directives and invokes the specified {@code callback}
     * with the directive names and values.
     */
    public static void parseDirectives(String directives, BiConsumer<String, String> callback) {
        final int len = directives.length();
        for (int i = 0; i < len;) {
            final int nameStart = i;
            final String name;
            final String value;

            // Find the name.
            for (; i < len; i++) {
                final char ch = directives.charAt(i);
                if (ch == ',' || ch == '=') {
                    break;
                }
            }
            name = directives.substring(nameStart, i).trim();

            // Find the value.
            if (i == len || directives.charAt(i) == ',') {
                // Skip comma or go beyond 'len' to break the loop.
                i++;
                value = null;
            } else {
                // Skip '='.
                i++;

                // Skip whitespaces.
                for (; i < len; i++) {
                    final char ch = directives.charAt(i);
                    if (ch != ' ' && ch != '\t') {
                        break;
                    }
                }

                if (i < len && directives.charAt(i) == '\"') {
                    // Handle quoted string.
                    // Skip the opening quote.
                    i++;
                    final int valueStart = i;

                    // Find the closing quote.
                    for (; i < len; i++) {
                        if (directives.charAt(i) == '\"') {
                            break;
                        }
                    }
                    value = directives.substring(valueStart, i);

                    // Skip the closing quote.
                    i++;

                    // Find the comma and skip it.
                    for (; i < len; i++) {
                        if (directives.charAt(i) == ',') {
                            i++;
                            break;
                        }
                    }
                } else {
                    // Handle unquoted string.
                    final int valueStart = i;

                    // Find the comma.
                    for (; i < len; i++) {
                        if (directives.charAt(i) == ',') {
                            break;
                        }
                    }
                    value = directives.substring(valueStart, i).trim();

                    // Skip the comma.
                    i++;
                }
            }

            if (!name.isEmpty()) {
                callback.accept(Ascii.toLowerCase(name), Strings.emptyToNull(value));
            }
        }
    }

    /**
     * Converts the specified HTTP header directive value into a long integer.
     *
     * @return the converted value if {@code value} is equal to or greater than {@code 0}.
     *         {@code -1} otherwise, i.e. if a negative integer or not a number.
     */
    public static long parseDirectiveValueAsSeconds(@Nullable String value) {
        if (value == null) {
            return -1;
        }

        try {
            final long converted = Long.parseLong(value);
            return converted >= 0 ? converted : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Converts the specified Netty HTTP/2 into Armeria HTTP/2 {@link RequestHeaders}.
     */
    public static RequestHeaders toArmeriaRequestHeaders(ChannelHandlerContext ctx, Http2Headers headers,
                                                         boolean endOfStream, String scheme,
                                                         ServerConfig cfg) {
        assert headers instanceof ArmeriaHttp2Headers;
        final HttpHeadersBuilder builder = ((ArmeriaHttp2Headers) headers).delegate();
        builder.endOfStream(endOfStream);
        // A CONNECT request might not have ":scheme". See https://datatracker.ietf.org/doc/html/rfc7540#section-8.1.2.3
        if (!builder.contains(HttpHeaderNames.SCHEME)) {
            builder.add(HttpHeaderNames.SCHEME, scheme);
        }

        if (builder.get(HttpHeaderNames.AUTHORITY) == null && builder.get(HttpHeaderNames.HOST) == null) {
            final String defaultHostname = cfg.defaultVirtualHost().defaultHostname();
            final int port = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
            builder.add(HttpHeaderNames.AUTHORITY, defaultHostname + ':' + port);
        }
        final List<String> cookies = builder.getAll(HttpHeaderNames.COOKIE);
        if (cookies.size() > 1) {
            // Cookies must be concatenated into a single octet string.
            // https://datatracker.ietf.org/doc/html/rfc7540#section-8.1.2.5
            builder.set(HttpHeaderNames.COOKIE, COOKIE_JOINER.join(cookies));
        }
        return RequestHeaders.of(builder.build());
    }

    /**
     * Converts the specified Netty HTTP/2 into Armeria HTTP/2 headers.
     */
    public static HttpHeaders toArmeria(Http2Headers http2Headers, boolean request, boolean endOfStream) {
        assert http2Headers instanceof ArmeriaHttp2Headers;
        final HttpHeadersBuilder delegate = ((ArmeriaHttp2Headers) http2Headers).delegate();
        delegate.endOfStream(endOfStream);
        HttpHeaders headers = delegate.build();

        if (request) {
            if (headers.contains(HttpHeaderNames.METHOD)) {
                headers = RequestHeaders.of(headers);
            }
            // http2Headers should be a trailers
        } else {
            if (headers.contains(HttpHeaderNames.STATUS)) {
                headers = ResponseHeaders.of(headers);
            }
        }
        return headers;
    }

    /**
     * Converts the headers of the given Netty HTTP/1.x request into Armeria HTTP/2 headers.
     * The following headers are only used if they can not be found in the {@code HOST} header or the
     * {@code Request-Line} as defined by <a href="https://datatracker.ietf.org/doc/rfc7230/">rfc7230</a>
     * <ul>
     * <li>{@link ExtensionHeaderNames#SCHEME}</li>
     * </ul>
     * {@link ExtensionHeaderNames#PATH} is ignored and instead extracted from the {@code Request-Line}.
     */
    public static RequestHeaders toArmeria(ChannelHandlerContext ctx, HttpRequest in,
                                           ServerConfig cfg, String scheme) throws URISyntaxException {

        final String path = in.uri();
        if (path.charAt(0) != '/' && !"*".equals(path)) {
            // We support only origin form and asterisk form.
            throw new URISyntaxException(path, "neither origin form nor asterisk form");
        }

        final io.netty.handler.codec.http.HttpHeaders inHeaders = in.headers();
        final RequestHeadersBuilder out = RequestHeaders.builder();
        out.sizeHint(inHeaders.size());
        out.method(HttpMethod.valueOf(in.method().name()))
           .path(path)
           .scheme(scheme);

        // Add the HTTP headers which have not been consumed above
        toArmeria(inHeaders, out);
        if (!out.contains(HttpHeaderNames.HOST)) {
            // The client violates the spec that the request headers must contain a Host header.
            // But we just add Host header to allow the request.
            // https://datatracker.ietf.org/doc/html/rfc7230#section-5.4
            final String defaultHostname = cfg.defaultVirtualHost().defaultHostname();
            final int port = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
            out.add(HttpHeaderNames.HOST, defaultHostname + ':' + port);
        }
        return out.build();
    }

    /**
     * Converts the headers of the given Netty HTTP/1.x response into Armeria HTTP/2 headers.
     */
    public static ResponseHeaders toArmeria(HttpResponse in) {
        final io.netty.handler.codec.http.HttpHeaders inHeaders = in.headers();
        final ResponseHeadersBuilder out = ResponseHeaders.builder();
        out.sizeHint(inHeaders.size());
        out.status(HttpStatus.valueOf(in.status().code()));
        // Add the HTTP headers which have not been consumed above
        toArmeria(inHeaders, out);
        return out.build();
    }

    /**
     * Converts the specified Netty HTTP/1 headers into Armeria HTTP/2 headers.
     */
    public static HttpHeaders toArmeria(io.netty.handler.codec.http.HttpHeaders inHeaders) {
        if (inHeaders.isEmpty()) {
            return HttpHeaders.of();
        }

        final HttpHeadersBuilder out = HttpHeaders.builder();
        out.sizeHint(inHeaders.size());
        toArmeria(inHeaders, out);
        return out.build();
    }

    /**
     * Converts the specified Netty HTTP/1 headers into Armeria HTTP/2 headers.
     */
    public static void toArmeria(io.netty.handler.codec.http.HttpHeaders inHeaders, HttpHeadersBuilder out) {
        final Iterator<Entry<CharSequence, CharSequence>> iter = inHeaders.iteratorCharSequence();
        // Choose 8 as a default size because it is unlikely we will see more than 4 Connection headers values,
        // but still allowing for "enough" space in the map to reduce the chance of hash code collision.
        final CaseInsensitiveMap connectionDisallowedList =
                toLowercaseMap(inHeaders.valueCharSequenceIterator(HttpHeaderNames.CONNECTION), 8);
        StringJoiner cookieJoiner = null;
        while (iter.hasNext()) {
            final Entry<CharSequence, CharSequence> entry = iter.next();
            final AsciiString aName = HttpHeaderNames.of(entry.getKey()).toLowerCase();
            if (HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST.contains(aName) ||
                connectionDisallowedList.contains(aName)) {
                continue;
            }

            // https://datatracker.ietf.org/doc/html/rfc7540#section-8.1.2.2 makes a special exception for TE
            if (aName.equals(HttpHeaderNames.TE)) {
                toHttp2HeadersFilterTE(entry, out);
                continue;
            }

            // Cookies must be concatenated into a single octet string.
            // https://datatracker.ietf.org/doc/html/rfc7540#section-8.1.2.5
            final CharSequence value = entry.getValue();
            if (aName.equals(HttpHeaderNames.COOKIE)) {
                if (cookieJoiner == null) {
                    cookieJoiner = new StringJoiner(COOKIE_SEPARATOR);
                }
                COOKIE_SPLITTER.split(value).forEach(cookieJoiner::add);
            } else {
                out.add(aName, convertHeaderValue(aName, value));
            }
        }

        if (cookieJoiner != null && cookieJoiner.length() != 0) {
            out.add(HttpHeaderNames.COOKIE, cookieJoiner.toString());
        }
    }

    private static CaseInsensitiveMap toLowercaseMap(Iterator<? extends CharSequence> valuesIter,
                                                     int arraySizeHint) {
        final CaseInsensitiveMap result = new CaseInsensitiveMap(arraySizeHint);

        while (valuesIter.hasNext()) {
            final AsciiString lowerCased = AsciiString.of(valuesIter.next()).toLowerCase();
            try {
                int index = lowerCased.forEachByte(FIND_COMMA);
                if (index != -1) {
                    int start = 0;
                    do {
                        result.add(lowerCased.subSequence(start, index, false).trim(), EMPTY_STRING);
                        start = index + 1;
                    } while (start < lowerCased.length() &&
                             (index = lowerCased.forEachByte(start,
                                                             lowerCased.length() - start, FIND_COMMA)) != -1);
                    result.add(lowerCased.subSequence(start, lowerCased.length(), false).trim(), EMPTY_STRING);
                } else {
                    result.add(lowerCased.trim(), EMPTY_STRING);
                }
            } catch (Exception e) {
                // This is not expect to happen because FIND_COMMA never throws but must be caught
                // because of the ByteProcessor interface.
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    /**
     * Filter the {@link HttpHeaderNames#TE} header according to the
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-8.1.2.2">special rules in the HTTP/2 RFC</a>.
     *
     * @param entry the entry whose name is {@link HttpHeaderNames#TE}.
     * @param out the resulting HTTP/2 headers.
     */
    private static void toHttp2HeadersFilterTE(Entry<CharSequence, CharSequence> entry,
                                               HttpHeadersBuilder out) {
        if (AsciiString.indexOf(entry.getValue(), ',', 0) == -1) {
            if (AsciiString.contentEqualsIgnoreCase(AsciiString.trim(entry.getValue()),
                                                    HttpHeaderValues.TRAILERS)) {
                out.add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS.toString());
            }
        } else {
            final List<CharSequence> teValues = StringUtil.unescapeCsvFields(entry.getValue());
            for (CharSequence teValue : teValues) {
                if (AsciiString.contentEqualsIgnoreCase(AsciiString.trim(teValue),
                                                        HttpHeaderValues.TRAILERS)) {
                    out.add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS.toString());
                    break;
                }
            }
        }
    }

    /**
     * Converts the specified Armeria HTTP/2 {@link ResponseHeaders} into Netty HTTP/2 headers.
     *
     * @param inputHeaders the HTTP/2 response headers to convert.
     */
    public static Http2Headers toNettyHttp2ServerHeaders(HttpHeadersBuilder inputHeaders) {
        for (Entry<AsciiString, AsciiString> disallowed : HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST) {
            inputHeaders.remove(disallowed.getKey());
        }
        // TODO(ikhoon): Implement HttpHeadersBuilder.remove(Predicate<AsciiString>) to remove values
        //               with a predicate.
        for (AsciiString disallowed : disallowedResponseHeaderNames()) {
            inputHeaders.remove(disallowed);
        }
        return new ArmeriaHttp2Headers(inputHeaders);
    }

    /**
     * Converts the specified Armeria HTTP/2 response headers into Netty HTTP/2 headers.
     *
     * @param inputHeaders the HTTP/2 response headers to convert.
     */
    public static Http2Headers toNettyHttp2ServerTrailers(HttpHeaders inputHeaders) {
        final HttpHeadersBuilder builder = inputHeaders.toBuilder();

        for (Entry<AsciiString, AsciiString> disallowed : HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST) {
            builder.remove(disallowed.getKey());
        }
        for (AsciiString disallowed : PSEUDO_HEADERS) {
           builder.remove(disallowed);
        }
        for (Entry<AsciiString, AsciiString> disallowed : HTTP_TRAILER_DISALLOWED_LIST) {
            builder.remove(disallowed.getKey());
        }

        return new ArmeriaHttp2Headers(builder);
    }

    /**
     * Converts the specified Armeria HTTP/2 request headers into Netty HTTP/2 headers.
     *
     * @param inputHeaders the HTTP/2 request headers to convert.
     */
    public static Http2Headers toNettyHttp2ClientHeaders(HttpHeaders inputHeaders) {
        final int headerSizeHint = inputHeaders.size() + 3; // User_Agent, :scheme and :authority.
        final Http2Headers outputHeaders = new DefaultHttp2Headers(false, headerSizeHint);
        toNettyHttp2Client(inputHeaders, outputHeaders, false);
        return outputHeaders;
    }

    /**
     * Converts the specified Armeria HTTP/2 request headers into Netty HTTP/2 headers.
     *
     * @param inputHeaders the HTTP/2 request headers to convert.
     */
    public static Http2Headers toNettyHttp2ClientTrailers(HttpHeaders inputHeaders) {
        final int headerSizeHint = inputHeaders.size();
        final Http2Headers outputHeaders = new DefaultHttp2Headers(false, headerSizeHint);
        toNettyHttp2Client(inputHeaders, outputHeaders, true);
        return outputHeaders;
    }

    private static void toNettyHttp2Client(HttpHeaders inputHeaders, Http2Headers outputHeaders,
                                           boolean isTrailer) {
        for (Entry<AsciiString, String> entry : inputHeaders) {
            final AsciiString name = entry.getKey();
            final String value = entry.getValue();
            if (HTTP_TO_HTTP2_HEADER_DISALLOWED_LIST.contains(name)) {
                continue;
            }

            if (isTrailer && isTrailerDisallowed(name)) {
                continue;
            }

            outputHeaders.add(name, value);
        }

        if (!outputHeaders.contains(HttpHeaderNames.COOKIE)) {
            return;
        }

        // Split up cookies to allow for better compression.
        // https://datatracker.ietf.org/doc/html/rfc7540#section-8.1.2.5
        final List<CharSequence> cookies = outputHeaders.getAllAndRemove(HttpHeaderNames.COOKIE);
        for (CharSequence c : cookies) {
            outputHeaders.add(HttpHeaderNames.COOKIE, COOKIE_SPLITTER.split(c));
        }
    }

    /**
     * Translates and adds HTTP/2 response headers to HTTP/1.1 headers.
     *
     * @param inputHeaders the HTTP/2 response headers to convert.
     * @param outputHeaders the object which will contain the resulting HTTP/1.1 headers.
     */
    public static void toNettyHttp1ServerHeaders(
            HttpHeaders inputHeaders, io.netty.handler.codec.http.HttpHeaders outputHeaders,
            Http1HeaderNaming http1HeaderNaming) {
        toNettyHttp1Server(inputHeaders, outputHeaders, http1HeaderNaming, false);
        HttpUtil.setKeepAlive(outputHeaders, HttpVersion.HTTP_1_1, true);
    }

    /**
     * Translates and adds HTTP/2 response trailers to HTTP/1.1 headers.
     *
     * @param inputHeaders The HTTP/2 response headers to convert.
     * @param outputHeaders The object which will contain the resulting HTTP/1.1 headers.
     */
    public static void toNettyHttp1ServerTrailers(
            HttpHeaders inputHeaders, io.netty.handler.codec.http.HttpHeaders outputHeaders,
            Http1HeaderNaming http1HeaderNaming) {
        toNettyHttp1Server(inputHeaders, outputHeaders, http1HeaderNaming, true);
    }

    private static void toNettyHttp1Server(
            HttpHeaders inputHeaders, io.netty.handler.codec.http.HttpHeaders outputHeaders,
            Http1HeaderNaming http1HeaderNaming, boolean isTrailer) {
        for (Entry<AsciiString, String> entry : inputHeaders) {
            final AsciiString name = entry.getKey();
            final String value = entry.getValue();
            if (HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST.contains(name)) {
                continue;
            }

            if (isTrailer && isTrailerDisallowed(name)) {
                continue;
            }
            outputHeaders.add(http1HeaderNaming.convert(name), value);
        }
    }

    /**
     * Translates and adds HTTP/2 request headers to HTTP/1.1 headers.
     *
     * @param inputHeaders the HTTP/2 request headers to convert.
     * @param outputHeaders the object which will contain the resulting HTTP/1.1 headers.
     */
    public static void toNettyHttp1ClientHeaders(
            HttpHeaders inputHeaders, io.netty.handler.codec.http.HttpHeaders outputHeaders,
            Http1HeaderNaming http1HeaderNaming) {
        toNettyHttp1Client(inputHeaders, outputHeaders, http1HeaderNaming, false);
        HttpUtil.setKeepAlive(outputHeaders, HttpVersion.HTTP_1_1, true);
    }

    /**
     * Translates and adds HTTP/2 request headers to HTTP/1.1 headers.
     *
     * @param inputHeaders the HTTP/2 request headers to convert.
     * @param outputHeaders the object which will contain the resulting HTTP/1.1 headers.
     */
    public static void toNettyHttp1ClientTrailers(
            HttpHeaders inputHeaders, io.netty.handler.codec.http.HttpHeaders outputHeaders,
            Http1HeaderNaming http1HeaderNaming) {
        toNettyHttp1Client(inputHeaders, outputHeaders, http1HeaderNaming, true);
    }

    private static void toNettyHttp1Client(
            HttpHeaders inputHeaders, io.netty.handler.codec.http.HttpHeaders outputHeaders,
            Http1HeaderNaming http1HeaderNaming, boolean isTrailer) {
        StringJoiner cookieJoiner = null;

        for (Entry<AsciiString, String> entry : inputHeaders) {
            final AsciiString name = entry.getKey();
            final String value = entry.getValue();
            final AsciiString translatedName = REQUEST_HEADER_TRANSLATIONS.get(name);
            if (translatedName != null && !inputHeaders.contains(translatedName)) {
                outputHeaders.add(translatedName, value);
                continue;
            }

            if (HTTP2_TO_HTTP_HEADER_DISALLOWED_LIST.contains(name)) {
                continue;
            }

            if (isTrailer && isTrailerDisallowed(name)) {
                continue;
            }

            if (HttpHeaderNames.COOKIE.equals(name)) {
                // combine the cookie values into 1 header entry.
                // https://datatracker.ietf.org/doc/html/rfc7540#section-8.1.2.5
                if (cookieJoiner == null) {
                    cookieJoiner = new StringJoiner(COOKIE_SEPARATOR);
                }
                COOKIE_SPLITTER.split(value).forEach(cookieJoiner::add);
            } else {
                outputHeaders.add(http1HeaderNaming.convert(name), value);
            }
        }

        if (cookieJoiner != null && cookieJoiner.length() != 0) {
            outputHeaders.add(HttpHeaderNames.COOKIE, cookieJoiner.toString());
        }
    }

    /**
     * Returns a {@link ResponseHeaders} whose {@link HttpHeaderNames#CONTENT_LENGTH} is added or removed
     * according to the status of the specified {@code headers}, {@code content} and {@code trailers}.
     * The {@link HttpHeaderNames#CONTENT_LENGTH} is removed when:
     * <ul>
     *   <li>the status of the specified {@code headers} is one of {@link HttpStatus#NO_CONTENT},
     *       {@link HttpStatus#RESET_CONTENT} or {@link HttpStatus#NOT_MODIFIED}</li>
     *   <li>the trailers exists</li>
     * </ul>
     * The {@link HttpHeaderNames#CONTENT_LENGTH} is added when the state of the specified {@code headers}
     * does not meet the conditions above and {@link HttpHeaderNames#CONTENT_LENGTH} is not present
     * regardless of the fact that the content is empty or not.
     *
     * @throws IllegalArgumentException if the specified {@code content} is not empty when the specified
     *                                  {@link HttpStatus} is one of {@link HttpStatus#NO_CONTENT},
     *                                  {@link HttpStatus#RESET_CONTENT} and {@link HttpStatus#NOT_MODIFIED}.
     */
    public static ResponseHeaders setOrRemoveContentLength(ResponseHeaders headers, HttpData content,
                                                           HttpHeaders trailers) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailers, "trailers");

        final HttpStatus status = headers.status();

        if (isContentAlwaysEmptyWithValidation(status, content)) {
            if (status != HttpStatus.NOT_MODIFIED) {
                if (headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                    final ResponseHeadersBuilder builder = headers.toBuilder();
                    builder.remove(HttpHeaderNames.CONTENT_LENGTH);
                    return builder.build();
                }
            } else {
                // 304 response can have the "content-length" header when it is a response to a conditional
                // GET request. See https://datatracker.ietf.org/doc/html/rfc7230#section-3.3.2
            }

            return headers;
        }

        if (!trailers.isEmpty()) {
            // Some of the client implementations such as "curl" ignores trailers if
            // the "content-length" header is present. We should not set "content-length" header when
            // trailers exists so that those clients can receive the trailers.
            // The response is sent using chunked transfer encoding in HTTP/1 or a DATA frame payload
            // in HTTP/2, so it's no worry.
            if (headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                final ResponseHeadersBuilder builder = headers.toBuilder();
                builder.remove(HttpHeaderNames.CONTENT_LENGTH);
                return builder.build();
            }

            return headers;
        }

        if (!headers.contains(HttpHeaderNames.CONTENT_LENGTH) || !content.isEmpty()) {
            return headers.toBuilder()
                          .contentLength(content.length())
                          .build();
        }

        // The header contains "content-length" header and the content is empty.
        // Do not overwrite the header because a response to a HEAD request
        // will have no content even if it has non-zero content-length header.
        return headers;
    }

    public static String convertHeaderValue(AsciiString name, CharSequence value) {
        if (!(value instanceof AsciiString)) {
            return value.toString();
        }
        if (HEADER_VALUE_CACHE != null && CACHED_HEADERS.contains(name)) {
            final String converted = HEADER_VALUE_CACHE.get((AsciiString) value);
            assert converted != null; // loader does not return null.
            return converted;
        }
        return value.toString();
    }

    /**
     * Returns {@code true} if the specified header name is not allowed for HTTP trailers.
     */
    public static boolean isTrailerDisallowed(AsciiString name) {
        return HTTP_TRAILER_DISALLOWED_LIST.contains(name);
    }

    private static final class CaseInsensitiveMap
            extends DefaultHeaders<AsciiString, AsciiString, CaseInsensitiveMap> {

        CaseInsensitiveMap() {
            super(HTTP2_HEADER_NAME_HASHER, UnsupportedValueConverter.instance());
        }

        @SuppressWarnings("unchecked")
        CaseInsensitiveMap(int size) {
            super(HTTP2_HEADER_NAME_HASHER, UnsupportedValueConverter.instance(), NameValidator.NOT_NULL, size);
        }
    }

    /**
     * Returns a authority header value of specified host and port.
     */
    public static String authorityHeader(String host, int port, int defaultPort) {
        if (port == defaultPort) {
            return host;
        } else {
            final StringBuilder buf = new StringBuilder(host.length() + 6);
            buf.append(host);
            buf.append(':');
            buf.append(port);
            return buf.toString();
        }
    }

    /**
     * A 408 Request Timeout response can be received even without a request.
     * More details can be found at https://github.com/line/armeria/issues/3055.
     */
    public static boolean isRequestTimeoutResponse(HttpResponse httpResponse) {
        return httpResponse.status() == HttpResponseStatus.REQUEST_TIMEOUT &&
               "close".equalsIgnoreCase(httpResponse.headers().get(HttpHeaderNames.CONNECTION));
    }

    private ArmeriaHttpUtil() {}
}
