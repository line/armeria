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

import static io.netty.handler.codec.http.HttpUtil.isAsteriskForm;
import static io.netty.handler.codec.http.HttpUtil.isOriginForm;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.streamError;
import static io.netty.util.AsciiString.EMPTY_STRING;
import static io.netty.util.internal.StringUtil.isNullOrEmpty;
import static io.netty.util.internal.StringUtil.length;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.DefaultHttpHeaders;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;

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

/**
 * Provides various utility functions for internal use related with HTTP.
 *
 * <p>The conversion between HTTP/1 and HTTP/2 has been forked from Netty's {@link HttpConversionUtil}.
 */
public final class ArmeriaHttpUtil {

    /**
     * According to RFC 3986 section 3.3, path can contain a colon, except the first segment.
     *
     * <p>Should allow the asterisk character in the path, query, or fragment components of a URL(RFC2396).
     * @see <a href="https://tools.ietf.org/html/rfc3986#section-3.3">RFC 3986, section 3.3</a>
     */
    private static final Pattern PROHIBITED_PATH_PATTERN =
            Pattern.compile("^/[^/]*:[^/]*/|[|<>\\\\]|/\\.\\.|\\.\\.$|\\.\\./");

    private static final Pattern CONSECUTIVE_SLASHES_PATTERN = Pattern.compile("/{2,}");

    /**
     * The default case-insensitive {@link AsciiString} hasher and comparator for HTTP/2 headers.
     */
    public static final HashingStrategy<AsciiString> HTTP2_HEADER_NAME_HASHER =
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

    private static final URI ROOT = URI.create("/");

    /**
     * The set of headers that should not be directly copied when converting headers from HTTP/1 to HTTP/2.
     */
    private static final CharSequenceMap HTTP_TO_HTTP2_HEADER_BLACKLIST = new CharSequenceMap();

    /**
     * The set of headers that should not be directly copied when converting headers from HTTP/2 to HTTP/1.
     */
    private static final CharSequenceMap HTTP2_TO_HTTP_HEADER_BLACKLIST = new CharSequenceMap();

    static {
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(HttpHeaderNames.CONNECTION, EMPTY_STRING);
        @SuppressWarnings("deprecation")
        final AsciiString keepAlive = HttpHeaderNames.KEEP_ALIVE;
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(keepAlive, EMPTY_STRING);
        @SuppressWarnings("deprecation")
        final AsciiString proxyConnection = HttpHeaderNames.PROXY_CONNECTION;
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(proxyConnection, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(HttpHeaderNames.TRANSFER_ENCODING, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(HttpHeaderNames.HOST, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(HttpHeaderNames.UPGRADE, EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(ExtensionHeaderNames.STREAM_ID.text(), EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(ExtensionHeaderNames.SCHEME.text(), EMPTY_STRING);
        HTTP_TO_HTTP2_HEADER_BLACKLIST.add(ExtensionHeaderNames.PATH.text(), EMPTY_STRING);

        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(HttpHeaderNames.AUTHORITY, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(HttpHeaderNames.METHOD, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(HttpHeaderNames.PATH, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(HttpHeaderNames.SCHEME, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(HttpHeaderNames.STATUS, EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(ExtensionHeaderNames.STREAM_ID.text(), EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(ExtensionHeaderNames.SCHEME.text(), EMPTY_STRING);
        HTTP2_TO_HTTP_HEADER_BLACKLIST.add(ExtensionHeaderNames.PATH.text(), EMPTY_STRING);
    }

    /**
     * Validates the {@link String} that contains an absolute path and a query, and splits them into
     * the path part and the query part.
     *
     * @return a two-element array whose first element is an absolute path and the second element is a query.
     *         {@code null} if the specified {@link String} is not an absolute path or invalid.
     */
    public static String[] splitPathAndQuery(final String pathAndQuery) {
        final String path;
        final String query;

        if (isNullOrEmpty(pathAndQuery)) {
            // e.g. http://example.com
            path = "/";
            query = null;
        } else if (pathAndQuery.charAt(0) != '/') {
            // Do not accept a relative path.
            return null;
        } else {
            // Split by the first '?'.
            final int queryPos = pathAndQuery.indexOf('?');
            if (queryPos >= 0) {
                path = pathAndQuery.substring(0, queryPos);
                query = pathAndQuery.substring(queryPos + 1);
            } else {
                path = pathAndQuery;
                query = null;
            }
        }

        // Make sure the path and the query are encoded correctly. i.e. Do not pass poorly encoded paths
        // and queries to services. However, do not pass the decoded paths and queries to the services,
        // so that users have more control over the encoding.
        if (!isValidEncoding(path) ||
            !isValidEncoding(query)) {
            return null;
        }

        // Reject the prohibited patterns.
        if (PROHIBITED_PATH_PATTERN.matcher(path).find()) {
            return null;
        }

        // Work around the case where a client sends a path such as '/path//with///consecutive////slashes'.
        return new String[] { CONSECUTIVE_SLASHES_PATTERN.matcher(path).replaceAll("/"), query };
    }

    @SuppressWarnings({ "DuplicateCondition", "DuplicateBooleanBranch" })
    private static boolean isValidEncoding(String value) {
        if (value == null) {
            return true;
        }

        final int length = value.length();
        for (int i = 0; i < length; i++) {
            final char ch = value.charAt(i);
            if (ch != '%') {
                continue;
            }

            final int end = i + 3;
            if (end > length) {
                // '%' or '%x' (must be followed by two hexadigits)
                return false;
            }

            if (!isHexadigit(value.charAt(++i)) ||
                !isHexadigit(value.charAt(++i))) {
                // The first or second digit is not hexadecimal.
                return false;
            }
        }

        return true;
    }

    private static boolean isHexadigit(char ch) {
        return ch >= '0' && ch <= '9' ||
               ch >= 'a' && ch <= 'f' ||
               ch >= 'A' && ch <= 'F';
    }

    /**
     * Concatenates two path strings.
     */
    public static String concatPaths(String path1, String path2) {
        path2 = path2 == null ? "" : path2;

        if (path1 == null || path1.isEmpty() || "/".equals(path1)) {
            if (path2.isEmpty()) {
                return "/";
            }

            if (path2.charAt(0) == '/') {
                return path2; // Most requests will land here.
            }

            return new StringBuilder(path2.length() + 1)
                    .append('/').append(path2).toString();
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
            return new StringBuilder(path1.length() + path2.length())
                    .append(path1).append(path2).toString();
        }

        if (path2.charAt(0) == '/') {
            // path1 does not end with '/' and path2 starts with '/'.
            // Simple concatenation would suffice.
            return path1 + path2;
        }

        // path1 does not end with '/' and path2 does not start with '/'.
        // Need to insert '/' between path1 and path2.
        return new StringBuilder(path1.length() + path2.length() + 1)
                .append(path1).append('/').append(path2).toString();
    }

    /**
     * Returns {@code true} if the content of the response with the given {@link HttpStatus} is expected to
     * be always empty (1xx, 204, 205 and 304 responses.)
     */
    public static boolean isContentAlwaysEmpty(HttpStatus status) {
        if (status.codeClass() == HttpStatusClass.INFORMATIONAL) {
            return true;
        }

        switch (status.code()) {
            case 204: case 205: case 304:
                return true;
        }

        return false;
    }

    /**
     * Returns {@code true} if the content of the response with the given {@link HttpStatus} is expected to
     * be always empty (1xx, 204, 205 and 304 responses.)
     *
     * @throws IllegalArgumentException if the specified {@code content} or {@code trailingHeaders} are
     *                                  non-empty when the content is always empty
     */
    public static boolean isContentAlwaysEmptyWithValidation(
            HttpStatus status, HttpData content, HttpHeaders trailingHeaders) {
        if (!isContentAlwaysEmpty(status)) {
            return false;
        }

        if (!content.isEmpty()) {
            throw new IllegalArgumentException(
                    "A " + status + " response must have empty content: " + content.length() + " byte(s)");
        }
        if (!trailingHeaders.isEmpty()) {
            throw new IllegalArgumentException(
                    "A " + status + " response must not have trailing headers: " + trailingHeaders);
        }

        return true;
    }

    /**
     * Converts the specified Netty HTTP/2 into Armeria HTTP/2 headers.
     */
    public static HttpHeaders toArmeria(Http2Headers headers) {
        final HttpHeaders converted = new DefaultHttpHeaders(false, headers.size());
        for (Entry<CharSequence, CharSequence> e : headers) {
            converted.add(AsciiString.of(e.getKey()), e.getValue().toString());
        }
        return converted;
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
    public static HttpHeaders toArmeria(HttpRequest in) throws URISyntaxException {
        final URI requestTargetUri = toUri(in);

        final io.netty.handler.codec.http.HttpHeaders inHeaders = in.headers();
        final HttpHeaders out = new DefaultHttpHeaders(true, inHeaders.size());

        out.path(toHttp2Path(requestTargetUri));
        out.method(HttpMethod.valueOf(in.method().name()));
        setHttp2Scheme(inHeaders, requestTargetUri, out);

        if (!isOriginForm(requestTargetUri) && !isAsteriskForm(requestTargetUri)) {
            // Attempt to take from HOST header before taking from the request-line
            String host = inHeaders.getAsString(HttpHeaderNames.HOST);
            setHttp2Authority(host == null || host.isEmpty() ? requestTargetUri.getAuthority() : host, out);
        }

        // Add the HTTP headers which have not been consumed above
        toArmeria(inHeaders, out);
        return out;
    }

    /**
     * Converts the headers of the given Netty HTTP/1.x response into Armeria HTTP/2 headers.
     */
    public static HttpHeaders toArmeria(HttpResponse in) {
        io.netty.handler.codec.http.HttpHeaders inHeaders = in.headers();
        final HttpHeaders out = new DefaultHttpHeaders(true, inHeaders.size());
        out.status(in.status().code());

        // Add the HTTP headers which have not been consumed above
        toArmeria(inHeaders, out);
        return out;
    }

    /**
     * Converts the specified Netty HTTP/1 headers into Armeria HTTP/2 headers.
     */
    public static HttpHeaders toArmeria(io.netty.handler.codec.http.HttpHeaders inHeaders) {
        if (inHeaders.isEmpty()) {
            return HttpHeaders.EMPTY_HEADERS;
        }

        final HttpHeaders out = new DefaultHttpHeaders(true, inHeaders.size());
        toArmeria(inHeaders, out);
        return out;
    }

    /**
     * Converts the specified Netty HTTP/1 headers into Armeria HTTP/2 headers.
     */
    public static void toArmeria(io.netty.handler.codec.http.HttpHeaders inHeaders, HttpHeaders out) {
        final Iterator<Entry<CharSequence, CharSequence>> i = inHeaders.iteratorCharSequence();
        while (i.hasNext()) {
            final Entry<CharSequence, CharSequence> entry = i.next();
            final AsciiString aName = AsciiString.of(entry.getKey()).toLowerCase();
            if (!HTTP_TO_HTTP2_HEADER_BLACKLIST.contains(aName)) {
                // https://tools.ietf.org/html/rfc7540#section-8.1.2.2 makes a special exception for TE
                if (aName.contentEqualsIgnoreCase(HttpHeaderNames.TE) &&
                    !AsciiString.contentEqualsIgnoreCase(entry.getValue(), HttpHeaderValues.TRAILERS)) {
                    continue;
                }

                out.add(aName, entry.getValue().toString());
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

        return pathBuilder.toString();
    }

    private static void setHttp2Authority(String authority, HttpHeaders out) {
        // The authority MUST NOT include the deprecated "userinfo" subcomponent
        if (authority != null) {
            int endOfUserInfo = authority.indexOf('@');
            if (endOfUserInfo < 0) {
                out.authority(authority);
            } else if (endOfUserInfo + 1 < authority.length()) {
                out.authority(authority.substring(endOfUserInfo + 1));
            } else {
                throw new IllegalArgumentException("authority: " + authority);
            }
        }
    }

    private static void setHttp2Scheme(io.netty.handler.codec.http.HttpHeaders in, URI uri, HttpHeaders out) {
        String value = uri.getScheme();
        if (value != null) {
            out.scheme(value);
            return;
        }

        // Consume the Scheme extension header if present
        CharSequence cValue = in.get(ExtensionHeaderNames.SCHEME.text());
        if (cValue != null) {
            out.scheme(cValue.toString());
        } else {
            out.scheme("unknown");
        }
    }

    /**
     * Converts the specified Armeria HTTP/2 headers into Netty HTTP/2 headers.
     */
    public static Http2Headers toNettyHttp2(HttpHeaders inputHeaders) {
        final Http2Headers outputHeaders = new DefaultHttp2Headers(false, inputHeaders.size());
        outputHeaders.set(inputHeaders);
        outputHeaders.remove(HttpHeaderNames.CONNECTION);
        outputHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
        outputHeaders.remove(HttpHeaderNames.TRAILER);
        return outputHeaders;
    }

    /**
     * Converts the specified Armeria HTTP/2 headers into Netty HTTP/1 headers.
     */
    public static io.netty.handler.codec.http.HttpHeaders toNettyHttp1(HttpHeaders inputHeaders) {
        final io.netty.handler.codec.http.DefaultHttpHeaders outputHeaders =
                new io.netty.handler.codec.http.DefaultHttpHeaders();
        for (Entry<AsciiString, String> e : inputHeaders) {
            final AsciiString name = e.getKey();
            if (name.isEmpty() || HTTP2_TO_HTTP_HEADER_BLACKLIST.contains(name)) {
                continue;
            }
            outputHeaders.add(name, e.getValue());
        }
        return outputHeaders;
    }

    /**
     * Translate and add HTTP/2 headers to HTTP/1.x headers.
     *
     * @param streamId The stream associated with {@code sourceHeaders}.
     * @param inputHeaders The HTTP/2 headers to convert.
     * @param outputHeaders The object which will contain the resulting HTTP/1.x headers..
     * @param httpVersion What HTTP/1.x version {@code outputHeaders} should be treated as
     *                    when doing the conversion.
     * @param isTrailer {@code true} if {@code outputHeaders} should be treated as trailing headers.
     *                  {@code false} otherwise.
     * @param isRequest {@code true} if the {@code outputHeaders} will be used in a request message.
     *                  {@code false} for response message.
     *
     * @throws Http2Exception If not all HTTP/2 headers can be translated to HTTP/1.x.
     */
    public static void toNettyHttp1(
            int streamId, HttpHeaders inputHeaders, io.netty.handler.codec.http.HttpHeaders outputHeaders,
            HttpVersion httpVersion, boolean isTrailer, boolean isRequest) throws Http2Exception {

        final Http2ToHttpHeaderTranslator translator =
                new Http2ToHttpHeaderTranslator(outputHeaders, isRequest);
        try {
            for (Entry<AsciiString, String> entry : inputHeaders) {
                translator.translate(entry);
            }
        } catch (Throwable t) {
            throw streamError(streamId, PROTOCOL_ERROR, t, "HTTP/2 to HTTP/1.x headers conversion error");
        }

        outputHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
        outputHeaders.remove(HttpHeaderNames.TRAILER);
        if (!isTrailer) {
            HttpUtil.setKeepAlive(outputHeaders, httpVersion, true);
        }
    }

    /**
     * Utility which translates HTTP/2 headers to HTTP/1 headers.
     */
    private static final class Http2ToHttpHeaderTranslator {
        /**
         * Translations from HTTP/2 header name to the HTTP/1.x equivalent.
         */
        private static final CharSequenceMap REQUEST_HEADER_TRANSLATIONS = new CharSequenceMap();
        private static final CharSequenceMap RESPONSE_HEADER_TRANSLATIONS = new CharSequenceMap();

        static {
            RESPONSE_HEADER_TRANSLATIONS.add(Http2Headers.PseudoHeaderName.AUTHORITY.value(),
                                             HttpHeaderNames.HOST);
            REQUEST_HEADER_TRANSLATIONS.add(RESPONSE_HEADER_TRANSLATIONS);
        }

        private final io.netty.handler.codec.http.HttpHeaders output;
        private final CharSequenceMap translations;

        /**
         * Create a new instance.
         *
         * @param output The HTTP/1.x headers object to store the results of the translation
         * @param request if {@code true}, translates headers using the request translation map.
         *                Otherwise uses the response translation map.
         */
        Http2ToHttpHeaderTranslator(io.netty.handler.codec.http.HttpHeaders output,
                                    boolean request) {

            this.output = output;
            translations = request ? REQUEST_HEADER_TRANSLATIONS : RESPONSE_HEADER_TRANSLATIONS;
        }

        public void translate(Entry<AsciiString, String> entry) {
            final AsciiString name = entry.getKey();
            final String value = entry.getValue();
            AsciiString translatedName = translations.get(name);
            if (translatedName != null) {
                output.add(translatedName, value);
                return;
            }

            // https://tools.ietf.org/html/rfc7540#section-8.1.2.3
            if (name.isEmpty() || HTTP2_TO_HTTP_HEADER_BLACKLIST.contains(name)) {
                return;
            }

            if (HttpHeaderNames.COOKIE.equals(name)) {
                // combine the cookie values into 1 header entry.
                // https://tools.ietf.org/html/rfc7540#section-8.1.2.5
                String existingCookie = output.get(HttpHeaderNames.COOKIE);
                output.set(HttpHeaderNames.COOKIE,
                           existingCookie != null ? existingCookie + "; " + value : value);
            } else {
                output.add(name, value);
            }
        }
    }

    private static final class CharSequenceMap
            extends DefaultHeaders<AsciiString, AsciiString, CharSequenceMap> {

        CharSequenceMap() {
            super(HTTP2_HEADER_NAME_HASHER, UnsupportedValueConverter.instance());
        }
    }

    private ArmeriaHttpUtil() {}
}
