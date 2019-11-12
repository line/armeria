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
package com.linecorp.armeria.internal;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.netty.handler.codec.http.HttpUtil.isAsteriskForm;
import static io.netty.handler.codec.http.HttpUtil.isOriginForm;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.streamError;
import static io.netty.util.AsciiString.EMPTY_STRING;
import static io.netty.util.ByteProcessor.FIND_COMMA;
import static io.netty.util.internal.StringUtil.decodeHexNibble;
import static io.netty.util.internal.StringUtil.isNullOrEmpty;
import static io.netty.util.internal.StringUtil.length;
import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.Flags;
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
import com.linecorp.armeria.server.ServerConfig;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DefaultHeaders;
import io.netty.handler.codec.UnsupportedValueConverter;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Exception;
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
     * See https://tools.ietf.org/html/rfc2616#section-3.7.1
     */
    public static final Charset HTTP_DEFAULT_CONTENT_CHARSET = StandardCharsets.ISO_8859_1;

    /**
     * The old {@code "keep-alive"} header which has been superceded by {@code "connection"}.
     */
    public static final AsciiString HEADER_NAME_KEEP_ALIVE = AsciiString.cached("keep-alive");

    /**
     * The old {@code "proxy-connection"} header which has been superceded by {@code "connection"}.
     */
    public static final AsciiString HEADER_NAME_PROXY_CONNECTION = AsciiString.cached("proxy-connection");

    private static final URI ROOT = URI.create("/");

    /**
     * The set of headers that should not be directly copied when converting headers from HTTP/1 to HTTP/2.
     */
    private static final CharSequenceMap HTTP_TO_HTTP2_HEADER_BLACKLIST = new CharSequenceMap();

    /**
     * The set of headers that should not be directly copied when converting headers from HTTP/2 to HTTP/1.
     */
    private static final CharSequenceMap HTTP2_TO_HTTP_HEADER_BLACKLIST = new CharSequenceMap();

    /**
     * The set of headers that must not be directly copied when converting trailers.
     */
    private static final CharSequenceMap HTTP_TRAILER_BLACKLIST = new CharSequenceMap();

    static {
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(HttpHeaderNames.CONNECTION, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(HEADER_NAME_KEEP_ALIVE, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(HEADER_NAME_PROXY_CONNECTION, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(HttpHeaderNames.TRANSFER_ENCODING, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(HttpHeaderNames.HOST, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(HttpHeaderNames.UPGRADE, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(ExtensionHeaderNames.STREAM_ID.text(), EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(ExtensionHeaderNames.SCHEME.text(), EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(ExtensionHeaderNames.PATH.text(), EMPTY_STRING);

        // https://tools.ietf.org/html/rfc7540#section-8.1.2.3
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(HttpHeaderNames.AUTHORITY, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(HttpHeaderNames.METHOD, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(HttpHeaderNames.PATH, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(HttpHeaderNames.SCHEME, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(HttpHeaderNames.STATUS, EMPTY_STRING);

        // https://tools.ietf.org/html/rfc7540#section-8.1
        // The "chunked" transfer encoding defined in Section 4.1 of [RFC7230] MUST NOT be used in HTTP/2.
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(HttpHeaderNames.TRANSFER_ENCODING, EMPTY_STRING);

        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(ExtensionHeaderNames.STREAM_ID.text(), EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(ExtensionHeaderNames.SCHEME.text(), EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(ExtensionHeaderNames.PATH.text(), EMPTY_STRING);

        // https://tools.ietf.org/html/rfc7230#section-4.1.2
        // https://tools.ietf.org/html/rfc7540#section-8.1
        // A sender MUST NOT generate a trailer that contains a field necessary for message framing:
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.TRANSFER_ENCODING, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.CONTENT_LENGTH, EMPTY_STRING);

        // for request modifiers:
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.CACHE_CONTROL, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.EXPECT, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.HOST, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.MAX_FORWARDS, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.PRAGMA, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.RANGE, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.TE, EMPTY_STRING);

        // for authentication:
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.WWW_AUTHENTICATE, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.AUTHORIZATION, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.PROXY_AUTHENTICATE, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.PROXY_AUTHORIZATION, EMPTY_STRING);

        // for response control data:
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.DATE, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.LOCATION, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.RETRY_AFTER, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.VARY, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.WARNING, EMPTY_STRING);

        // or for determining how to process the payload:
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.CONTENT_ENCODING, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.CONTENT_TYPE, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.CONTENT_RANGE, EMPTY_STRING);
        HTTP_TRAILER_BLACKLIST.add(HttpHeaderNames.TRAILER, EMPTY_STRING);
    }

    /**
     * Translations from HTTP/2 header name to the HTTP/1.x equivalent. Currently, we expect these headers to
     * only allow a single value in the request. If adding headers that can potentially have multiple values,
     * please check the usage in code accordingly.
     */
    private static final CharSequenceMap REQUEST_HEADER_TRANSLATIONS = new CharSequenceMap();
    private static final CharSequenceMap RESPONSE_HEADER_TRANSLATIONS = new CharSequenceMap();

    static {
        RESPONSE_HEADER_TRANSLATIONS.add(Http2Headers.PseudoHeaderName.AUTHORITY.value(),
                                         HttpHeaderNames.HOST);
        REQUEST_HEADER_TRANSLATIONS.add(RESPONSE_HEADER_TRANSLATIONS);
    }

    /**
     * <a href="https://tools.ietf.org/html/rfc7540#section-8.1.2.3">rfc7540, 8.1.2.3</a> states the path must not
     * be empty, and instead should be {@code /}.
     */
    private static final String EMPTY_REQUEST_PATH = "/";

    private static final Splitter COOKIE_SPLITTER = Splitter.on(';').trimResults().omitEmptyStrings();
    private static final String COOKIE_SEPARATOR = "; ";

    @Nullable
    private static final LoadingCache<AsciiString, String> HEADER_VALUE_CACHE =
            Flags.headerValueCacheSpec().map(ArmeriaHttpUtil::buildCache).orElse(null);
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

        if (path2.charAt(0) == '/') {
            // path1 does not end with '/' and path2 starts with '/'.
            // Simple concatenation would suffice.
            return path1 + path2;
        }

        // path1 does not end with '/' and path2 does not start with '/'.
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
        final byte[] buf = ThreadLocalByteArray.get(len);
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
     * Returns {@code true} if the content of the response with the given {@link HttpStatus} is expected to
     * be always empty (1xx, 204, 205 and 304 responses.)
     *
     * @throws IllegalArgumentException if the specified {@code content} or {@code trailers} are
     *                                  non-empty when the content is always empty
     */
    public static boolean isContentAlwaysEmptyWithValidation(
            HttpStatus status, HttpData content, HttpHeaders trailers) {
        if (!status.isContentAlwaysEmpty()) {
            return false;
        }

        if (!content.isEmpty()) {
            throw new IllegalArgumentException(
                    "A " + status + " response must have empty content: " + content.length() + " byte(s)");
        }
        if (!trailers.isEmpty()) {
            throw new IllegalArgumentException(
                    "A " + status + " response must not have trailers: " + trailers);
        }

        return true;
    }

    /**
     * Returns {@code true} if the specified {@code request} is a CORS preflight request.
     */
    public static boolean isCorsPreflightRequest(com.linecorp.armeria.common.HttpRequest request) {
        requireNonNull(request, "request");
        return request.method() == HttpMethod.OPTIONS &&
               request.headers().contains(HttpHeaderNames.ORIGIN) &&
               request.headers().contains(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD);
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
        final RequestHeadersBuilder builder = RequestHeaders.builder();
        toArmeria(builder, headers, endOfStream);
        // A CONNECT request might not have ":scheme". See https://tools.ietf.org/html/rfc7540#section-8.1.2.3
        if (!builder.contains(HttpHeaderNames.SCHEME)) {
            builder.add(HttpHeaderNames.SCHEME, scheme);
        }
        if (!builder.contains(HttpHeaderNames.AUTHORITY)) {
            final String defaultHostname = cfg.defaultVirtualHost().defaultHostname();
            final int port = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
            builder.add(HttpHeaderNames.AUTHORITY, defaultHostname + ':' + port);
        }
        return builder.build();
    }

    /**
     * Converts the specified Netty HTTP/2 into Armeria HTTP/2 headers.
     */
    public static HttpHeaders toArmeria(Http2Headers headers, boolean request, boolean endOfStream) {
        final HttpHeadersBuilder builder;
        if (request) {
            builder = headers.contains(HttpHeaderNames.METHOD) ? RequestHeaders.builder()
                                                               : HttpHeaders.builder();
        } else {
            builder = headers.contains(HttpHeaderNames.STATUS) ? ResponseHeaders.builder()
                                                               : HttpHeaders.builder();
        }

        toArmeria(builder, headers, endOfStream);
        return builder.build();
    }

    private static void toArmeria(HttpHeadersBuilder builder, Http2Headers headers, boolean endOfStream) {
        builder.sizeHint(headers.size());
        builder.endOfStream(endOfStream);

        StringJoiner cookieJoiner = null;
        for (Entry<CharSequence, CharSequence> e : headers) {
            final AsciiString name = HttpHeaderNames.of(e.getKey());
            final CharSequence value = e.getValue();

            // Cookies must be concatenated into a single octet string.
            // https://tools.ietf.org/html/rfc7540#section-8.1.2.5
            if (name.equals(HttpHeaderNames.COOKIE)) {
                if (cookieJoiner == null) {
                    cookieJoiner = new StringJoiner(COOKIE_SEPARATOR);
                }
                COOKIE_SPLITTER.split(value).forEach(cookieJoiner::add);
            } else {
                builder.add(name, convertHeaderValue(name, value));
            }
        }

        if (cookieJoiner != null && cookieJoiner.length() != 0) {
            builder.add(HttpHeaderNames.COOKIE, cookieJoiner.toString());
        }
    }

    /**
     * Converts the headers of the given Netty HTTP/1.x request into Armeria HTTP/2 headers.
     * The following headers are only used if they can not be found in the {@code HOST} header or the
     * {@code Request-Line} as defined by <a href="https://tools.ietf.org/html/rfc7230">rfc7230</a>
     * <ul>
     * <li>{@link ExtensionHeaderNames#SCHEME}</li>
     * </ul>
     * {@link ExtensionHeaderNames#PATH} is ignored and instead extracted from the {@code Request-Line}.
     */
    public static RequestHeaders toArmeria(ChannelHandlerContext ctx, HttpRequest in,
                                           ServerConfig cfg) throws URISyntaxException {
        final URI requestTargetUri = toUri(in);

        final io.netty.handler.codec.http.HttpHeaders inHeaders = in.headers();
        final RequestHeadersBuilder out = RequestHeaders.builder();
        out.sizeHint(inHeaders.size());
        out.add(HttpHeaderNames.METHOD, in.method().name());
        out.add(HttpHeaderNames.PATH, toHttp2Path(requestTargetUri));

        addHttp2Scheme(inHeaders, requestTargetUri, out);

        if (!isOriginForm(requestTargetUri) && !isAsteriskForm(requestTargetUri)) {
            // Attempt to take from HOST header before taking from the request-line
            final String host = inHeaders.getAsString(HttpHeaderNames.HOST);
            addHttp2Authority(host == null || host.isEmpty() ? requestTargetUri.getAuthority() : host, out);
        }

        if (out.authority() == null) {
            final String defaultHostname = cfg.defaultVirtualHost().defaultHostname();
            final int port = ((InetSocketAddress) ctx.channel().localAddress()).getPort();
            out.add(HttpHeaderNames.AUTHORITY, defaultHostname + ':' + port);
        }

        // Add the HTTP headers which have not been consumed above
        toArmeria(inHeaders, out);
        return out.build();
    }

    /**
     * Converts the headers of the given Netty HTTP/1.x response into Armeria HTTP/2 headers.
     */
    public static ResponseHeaders toArmeria(HttpResponse in) {
        final io.netty.handler.codec.http.HttpHeaders inHeaders = in.headers();
        final ResponseHeadersBuilder out = ResponseHeaders.builder();
        out.sizeHint(inHeaders.size());
        out.add(HttpHeaderNames.STATUS, HttpStatus.valueOf(in.status().code()).codeAsText());
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
        final CharSequenceMap connectionBlacklist =
                toLowercaseMap(inHeaders.valueCharSequenceIterator(HttpHeaderNames.CONNECTION), 8);
        StringJoiner cookieJoiner = null;
        while (iter.hasNext()) {
            final Entry<CharSequence, CharSequence> entry = iter.next();
            final AsciiString aName = HttpHeaderNames.of(entry.getKey()).toLowerCase();
            if (HTTP_TO_HTTP2_HEADER_BLACKLIST.contains(aName) || connectionBlacklist.contains(aName)) {
                continue;
            }

            // https://tools.ietf.org/html/rfc7540#section-8.1.2.2 makes a special exception for TE
            if (aName.equals(HttpHeaderNames.TE)) {
                toHttp2HeadersFilterTE(entry, out);
                continue;
            }

            // Cookies must be concatenated into a single octet string.
            // https://tools.ietf.org/html/rfc7540#section-8.1.2.5
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

    private static CharSequenceMap toLowercaseMap(Iterator<? extends CharSequence> valuesIter,
                                                  int arraySizeHint) {
        final CharSequenceMap result = new CharSequenceMap(arraySizeHint);

        while (valuesIter.hasNext()) {
            final AsciiString lowerCased = HttpHeaderNames.of(valuesIter.next()).toLowerCase();
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
     * <a href="https://tools.ietf.org/html/rfc7540#section-8.1.2.2">special rules in the HTTP/2 RFC</a>.
     * @param entry An entry whose name is {@link HttpHeaderNames#TE}.
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

    private static URI toUri(HttpRequest in) throws URISyntaxException {
        final String uri = in.uri();
        if (uri.startsWith("//")) {
            // Normalize the path that starts with more than one slash into the one with a single slash,
            // so that java.net.URI does not raise a URISyntaxException.
            for (int i = 0; i < uri.length(); i++) {
                if (uri.charAt(i) != '/') {
                    return new URI(uri.substring(i - 1));
                }
            }
            return ROOT;
        } else {
            return new URI(uri);
        }
    }

    /**
     * Generate a HTTP/2 {code :path} from a URI in accordance with
     * <a href="https://tools.ietf.org/html/rfc7230#section-5.3">rfc7230, 5.3</a>.
     */
    private static String toHttp2Path(URI uri) {
        final StringBuilder pathBuilder = new StringBuilder(
                length(uri.getRawPath()) + length(uri.getRawQuery()) + length(uri.getRawFragment()) + 2);

        if (!isNullOrEmpty(uri.getRawPath())) {
            pathBuilder.append(uri.getRawPath());
        }
        if (!isNullOrEmpty(uri.getRawQuery())) {
            pathBuilder.append('?');
            pathBuilder.append(uri.getRawQuery());
        }
        if (!isNullOrEmpty(uri.getRawFragment())) {
            pathBuilder.append('#');
            pathBuilder.append(uri.getRawFragment());
        }

        return pathBuilder.length() != 0 ? pathBuilder.toString() : EMPTY_REQUEST_PATH;
    }

    @VisibleForTesting
    static void addHttp2Authority(@Nullable String authority, RequestHeadersBuilder out) {
        // The authority MUST NOT include the deprecated "userinfo" subcomponent
        if (authority != null) {
            final String actualAuthority;
            if (authority.isEmpty()) {
                actualAuthority = "";
            } else {
                final int start = authority.indexOf('@') + 1;
                if (start == 0) {
                    actualAuthority = authority;
                } else if (authority.length() == start) {
                    throw new IllegalArgumentException("authority: " + authority);
                } else {
                    actualAuthority = authority.substring(start);
                }
            }
            out.add(HttpHeaderNames.AUTHORITY, actualAuthority);
        }
    }

    private static void addHttp2Scheme(io.netty.handler.codec.http.HttpHeaders in, URI uri,
                                       RequestHeadersBuilder out) {
        final String value = uri.getScheme();
        if (value != null) {
            out.add(HttpHeaderNames.SCHEME, value);
            return;
        }

        // Consume the Scheme extension header if present
        final CharSequence cValue = in.get(ExtensionHeaderNames.SCHEME.text());
        if (cValue != null) {
            out.add(HttpHeaderNames.SCHEME, cValue.toString());
        } else {
            out.add(HttpHeaderNames.SCHEME, "unknown");
        }
    }

    /**
     * Converts the specified Armeria HTTP/2 headers into Netty HTTP/2 headers.
     */
    public static Http2Headers toNettyHttp2(HttpHeaders in, boolean server) {
        final Http2Headers out = new DefaultHttp2Headers(false, in.size());

        // Trailers if it does not have :status.
        if (server && !in.contains(HttpHeaderNames.STATUS)) {
            for (Entry<AsciiString, String> entry : in) {
                final AsciiString name = entry.getKey();
                final String value = entry.getValue();
                if (name.isEmpty() || isTrailerBlacklisted(name)) {
                    continue;
                }
                out.add(name, value);
            }
        } else {
            in.forEach((BiConsumer<AsciiString, String>) out::add);
            out.remove(HttpHeaderNames.CONNECTION);
            out.remove(HttpHeaderNames.TRANSFER_ENCODING);
        }

        if (!out.contains(HttpHeaderNames.COOKIE)) {
            return out;
        }

        // Split up cookies to allow for better compression.
        // https://tools.ietf.org/html/rfc7540#section-8.1.2.5
        final List<CharSequence> cookies = out.getAllAndRemove(HttpHeaderNames.COOKIE);
        for (CharSequence c : cookies) {
            out.add(HttpHeaderNames.COOKIE, COOKIE_SPLITTER.split(c));
        }

        return out;
    }

    /**
     * Translate and add HTTP/2 headers to HTTP/1.x headers.
     *
     * @param streamId The stream associated with {@code sourceHeaders}.
     * @param inputHeaders The HTTP/2 headers to convert.
     * @param outputHeaders The object which will contain the resulting HTTP/1.x headers..
     * @param httpVersion What HTTP/1.x version {@code outputHeaders} should be treated as
     *                    when doing the conversion.
     * @param isTrailer {@code true} if {@code outputHeaders} should be treated as trailers.
     *                  {@code false} otherwise.
     * @param isRequest {@code true} if the {@code outputHeaders} will be used in a request message.
     *                  {@code false} for response message.
     *
     * @throws Http2Exception If not all HTTP/2 headers can be translated to HTTP/1.x.
     */
    public static void toNettyHttp1(
            int streamId, HttpHeaders inputHeaders, io.netty.handler.codec.http.HttpHeaders outputHeaders,
            HttpVersion httpVersion, boolean isTrailer, boolean isRequest) throws Http2Exception {

        final CharSequenceMap translations = isRequest ? REQUEST_HEADER_TRANSLATIONS
                                                       : RESPONSE_HEADER_TRANSLATIONS;
        StringJoiner cookieJoiner = null;
        try {
            for (Entry<AsciiString, String> entry : inputHeaders) {
                final AsciiString name = entry.getKey();
                final String value = entry.getValue();
                final AsciiString translatedName = translations.get(name);
                if (translatedName != null && !inputHeaders.contains(translatedName)) {
                    outputHeaders.add(translatedName, value);
                    continue;
                }

                if (name.isEmpty() || HTTP2_TO_HTTP_HEADER_BLACKLIST.contains(name)) {
                    continue;
                }

                if (isTrailer && isTrailerBlacklisted(name)) {
                    continue;
                }

                if (HttpHeaderNames.COOKIE.equals(name)) {
                    // combine the cookie values into 1 header entry.
                    // https://tools.ietf.org/html/rfc7540#section-8.1.2.5
                    if (cookieJoiner == null) {
                        cookieJoiner = new StringJoiner(COOKIE_SEPARATOR);
                    }
                    COOKIE_SPLITTER.split(value).forEach(cookieJoiner::add);
                } else {
                    outputHeaders.add(name, value);
                }
            }

            if (cookieJoiner != null && cookieJoiner.length() != 0) {
                outputHeaders.add(HttpHeaderNames.COOKIE, cookieJoiner.toString());
            }
        } catch (Throwable t) {
            throw streamError(streamId, PROTOCOL_ERROR, t, "HTTP/2 to HTTP/1.x headers conversion error");
        }

        if (!isTrailer) {
            HttpUtil.setKeepAlive(outputHeaders, httpVersion, true);
        }
    }

    /**
     * Returns a {@link ResponseHeaders} whose {@link HttpHeaderNames#CONTENT_LENGTH} is added or removed
     * according to the status of the specified {@code headers}, {@code content} and {@code trailers}.
     * The {@link HttpHeaderNames#CONTENT_LENGTH} is removed when:
     * <ul>
     *   <li>the status of the specified {@code headers} is one of informational headers,
     *   {@link HttpStatus#NO_CONTENT} or {@link HttpStatus#RESET_CONTENT}</li>
     *   <li>the trailers exists</li>
     * </ul>
     * The {@link HttpHeaderNames#CONTENT_LENGTH} is added when the state of the specified {@code headers}
     * does not meet the conditions above and {@link HttpHeaderNames#CONTENT_LENGTH} is not present
     * regardless of the fact that the content is empty or not.
     *
     * @throws IllegalArgumentException if the specified {@code content} or {@code trailers} are
     *                                  non-empty when the content is always empty
     */
    public static ResponseHeaders setOrRemoveContentLength(ResponseHeaders headers, HttpData content,
                                                           HttpHeaders trailers) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        requireNonNull(trailers, "trailers");

        final HttpStatus status = headers.status();

        if (isContentAlwaysEmptyWithValidation(status, content, trailers)) {
            if (status != HttpStatus.NOT_MODIFIED) {
                if (headers.contains(HttpHeaderNames.CONTENT_LENGTH)) {
                    final ResponseHeadersBuilder builder = headers.toBuilder();
                    builder.remove(HttpHeaderNames.CONTENT_LENGTH);
                    return builder.build();
                }
            } else {
                // 304 response can have the "content-length" header when it is a response to a conditional
                // GET request. See https://tools.ietf.org/html/rfc7230#section-3.3.2
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
                          .setInt(HttpHeaderNames.CONTENT_LENGTH, content.length())
                          .build();
        }

        // The header contains "content-length" header and the content is empty.
        // Do not overwrite the header because a response to a HEAD request
        // will have no content even if it has non-zero content-length header.
        return headers;
    }

    private static String convertHeaderValue(AsciiString name, CharSequence value) {
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
     * Returns {@code true} if the specified header name is not allowed for HTTP tailers.
     */
    public static boolean isTrailerBlacklisted(AsciiString name) {
        return HTTP_TRAILER_BLACKLIST.contains(name);
    }

    private static final class CharSequenceMap
            extends DefaultHeaders<AsciiString, AsciiString, CharSequenceMap> {

        CharSequenceMap() {
            super(HTTP2_HEADER_NAME_HASHER, UnsupportedValueConverter.instance());
        }

        @SuppressWarnings("unchecked")
        CharSequenceMap(int size) {
            super(HTTP2_HEADER_NAME_HASHER, UnsupportedValueConverter.instance(), NameValidator.NOT_NULL, size);
        }
    }

    private ArmeriaHttpUtil() {}
}
