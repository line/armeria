/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linecorp.armeria.internal.http;

import static io.netty.handler.codec.http.HttpUtil.isAsteriskForm;
import static io.netty.handler.codec.http.HttpUtil.isOriginForm;
import static io.netty.handler.codec.http2.Http2Error.PROTOCOL_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.streamError;
import static io.netty.util.AsciiString.EMPTY_STRING;
import static io.netty.util.internal.StringUtil.isNullOrEmpty;
import static io.netty.util.internal.StringUtil.length;

import java.net.URI;
import java.util.Iterator;
import java.util.Map.Entry;

import com.linecorp.armeria.common.http.DefaultHttpHeaders;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.common.http.HttpStatusClass;

import io.netty.handler.codec.DefaultHeaders;
import io.netty.handler.codec.UnsupportedValueConverter;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMessage;
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
 * <p>
 * The conversion between HTTP/1 and HTTP/2 has been forked from Netty's {@link HttpConversionUtil}.
 */
public final class ArmeriaHttpUtil {

    /**
     * The default case-sensitive {@link AsciiString} hasher and comparator for HTTP/2 headers.
     */
    public static final HashingStrategy<AsciiString> HTTP2_HEADER_NAME_HASHER =
            new HashingStrategy<AsciiString>() {
                @Override
                public int hashCode(AsciiString o) {
                    return o.hashCode();
                }

                @Override
                public boolean equals(AsciiString a, AsciiString b) {
                    return a.equals(b);
                }
            };

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
     * Converts the headers of the given Netty HTTP/1.x message into Armeria HTTP/2 headers.
     * The following headers are only used if they can not be found in the {@code HOST} header or the
     * {@code Request-Line} as defined by <a href="https://tools.ietf.org/html/rfc7230">rfc7230</a>
     * <ul>
     * <li>{@link ExtensionHeaderNames#SCHEME}</li>
     * </ul>
     * {@link ExtensionHeaderNames#PATH} is ignored and instead extracted from the {@code Request-Line}.
     */
    public static HttpHeaders toArmeria(HttpMessage in) {
        io.netty.handler.codec.http.HttpHeaders inHeaders = in.headers();
        final HttpHeaders out = new DefaultHttpHeaders(true, inHeaders.size());
        if (in instanceof HttpRequest) {
            HttpRequest request = (HttpRequest) in;
            URI requestTargetUri = URI.create(request.uri());
            out.path(toHttp2Path(requestTargetUri));
            out.method(HttpMethod.valueOf(request.method().name()));
            setHttp2Scheme(inHeaders, requestTargetUri, out);

            if (!isOriginForm(requestTargetUri) && !isAsteriskForm(requestTargetUri)) {
                // Attempt to take from HOST header before taking from the request-line
                String host = inHeaders.getAsString(HttpHeaderNames.HOST);
                setHttp2Authority(host == null || host.isEmpty() ? requestTargetUri.getAuthority() : host, out);
            }
        } else if (in instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) in;
            out.status(response.status().code());
        }

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

    /**
     * Generate a HTTP/2 {code :path} from a URI in accordance with
     * <a href="https://tools.ietf.org/html/rfc7230#section-5.3">rfc7230, 5.3</a>.
     */
    private static String toHttp2Path(URI uri) {
        StringBuilder pathBuilder = new StringBuilder(length(uri.getRawPath()) +
                                                      length(uri.getRawQuery()) + length(uri.getRawFragment()) + 2);
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
     * @param httpVersion What HTTP/1.x version {@code outputHeaders} should be treated as when doing the conversion.
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
         * Create a new instance
         *
         * @param output The HTTP/1.x headers object to store the results of the translation
         * @param request if {@code true}, translates headers using the request translation map. Otherwise uses the
         *        response translation map.
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
