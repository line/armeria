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
import static io.netty.util.ByteProcessor.FIND_COMMA;
import static io.netty.util.internal.StringUtil.isNullOrEmpty;
import static io.netty.util.internal.StringUtil.length;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import com.google.common.annotations.VisibleForTesting;

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
import io.netty.util.internal.StringUtil;

/**
 * Provides various utility functions for internal use related with HTTP.
 *
 * <p>The conversion between HTTP/1 and HTTP/2 has been forked from Netty's {@link HttpConversionUtil}.
 */
public final class ArmeriaHttpUtil {

    // Forked from Netty at 7d213240ca768d6dd35ef2336b1fda757bd4df3c

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
     * <a href="https://tools.ietf.org/html/rfc7540#section-8.1.2.3">rfc7540, 8.1.2.3</a> states the path must not
     * be empty, and instead should be {@code /}.
     */
    private static final String EMPTY_REQUEST_PATH = "/";

    /**
     * Concatenates two path strings.
     */
    public static String concatPaths(String path1, String path2) {
        path2 = path2 == null ? "" : path2;

        if (path1 == null || path1.isEmpty() || EMPTY_REQUEST_PATH.equals(path1)) {
            if (path2.isEmpty()) {
                return EMPTY_REQUEST_PATH;
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
        final Iterator<Entry<CharSequence, CharSequence>> iter = inHeaders.iteratorCharSequence();
        // Choose 8 as a default size because it is unlikely we will see more than 4 Connection headers values,
        // but still allowing for "enough" space in the map to reduce the chance of hash code collision.
        final CharSequenceMap connectionBlacklist =
                toLowercaseMap(inHeaders.valueCharSequenceIterator(HttpHeaderNames.CONNECTION), 8);
        while (iter.hasNext()) {
            final Entry<CharSequence, CharSequence> entry = iter.next();
            final AsciiString aName = AsciiString.of(entry.getKey()).toLowerCase();
            if (!HTTP_TO_HTTP2_HEADER_BLACKLIST.contains(aName) && !connectionBlacklist.contains(aName)) {
                // https://tools.ietf.org/html/rfc7540#section-8.1.2.2 makes a special exception for TE
                if (aName.contentEqualsIgnoreCase(HttpHeaderNames.TE)) {
                    toHttp2HeadersFilterTE(entry, out);
                    continue;
                }

                final String value = entry.getValue().toString();
                if (aName.contentEqualsIgnoreCase(HttpHeaderNames.COOKIE)) {
                    // split up cookies to allow for better compression
                    // https://tools.ietf.org/html/rfc7540#section-8.1.2.5
                    int index = value.indexOf(';');
                    if (index >= 0) {
                        int start = 0;
                        do {
                            out.add(HttpHeaderNames.COOKIE, value.substring(start, index));
                            // skip 2 characters "; " (see https://tools.ietf.org/html/rfc6265#section-4.2.1)
                            index++;
                            if (index != value.length() && value.charAt(index) == ' ') {
                                index++;
                            }
                            start = index;
                        } while (start < value.length() &&
                                 (index = value.indexOf(';', start)) != -1);

                        if (start < value.length()) {
                            out.add(HttpHeaderNames.COOKIE, value.substring(start));
                        }
                    } else {
                        out.add(HttpHeaderNames.COOKIE, value);
                    }
                } else {
                    out.add(aName, value);
                }
            }
        }
    }

    private static CharSequenceMap toLowercaseMap(Iterator<? extends CharSequence> valuesIter,
                                                  int arraySizeHint) {
        CharSequenceMap result = new CharSequenceMap(arraySizeHint);

        while (valuesIter.hasNext()) {
            AsciiString lowerCased = AsciiString.of(valuesIter.next()).toLowerCase();
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
                                               HttpHeaders out) {
        if (AsciiString.indexOf(entry.getValue(), ',', 0) == -1) {
            if (AsciiString.contentEqualsIgnoreCase(AsciiString.trim(entry.getValue()),
                                                    HttpHeaderValues.TRAILERS)) {
                out.add(HttpHeaderNames.TE, HttpHeaderValues.TRAILERS.toString());
            }
        } else {
            List<CharSequence> teValues = StringUtil.unescapeCsvFields(entry.getValue());
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
    static void setHttp2Authority(String authority, HttpHeaders out) {
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
            out.authority(actualAuthority);
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

        @SuppressWarnings("unchecked")
        CharSequenceMap(int size) {
            super(HTTP2_HEADER_NAME_HASHER, UnsupportedValueConverter.instance(), NameValidator.NOT_NULL, size);
        }
    }

    private ArmeriaHttpUtil() {}
}
