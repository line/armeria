/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.servlet.util;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.servlet.http.Cookie;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;

import io.netty.util.CharsetUtil;

/**
 * Servlet Util.
 */
public abstract class ServletUtil {

    // Forked from https://github.com/netty/netty/tree/4.1/codec-http/src/main/java/io/netty/handler/codec/http

    private static final Logger logger = LoggerFactory.getLogger(ServletUtil.class);
    private static final char SPACE = 0x20;
    private static final int PARAM_LIMIT = 10000;
    private static final int NONE = 0;
    private static final int DATAHEADER = 1;

    /**
     * Decode by URL.
     */
    public static void decodeByUrl(LinkedMultiValueMap<String, String> parameterMap, String uri,
                                   Charset charset) {
        requireNonNull(parameterMap, "parameterMap");
        requireNonNull(uri, "uri");
        requireNonNull(charset, "charset");
        decodeParams(parameterMap, uri, findPathEndIndex(uri), charset, PARAM_LIMIT);
    }

    /**
     * Decode character encoding.
     */
    @Nullable
    public static String decodeCharacterEncoding(@Nullable String contentType) {
        if (contentType == null) {
            return null;
        }
        final int start = contentType.indexOf(HttpHeaderConstants.CHARSET + "=");
        if (start < 0) {
            return null;
        }
        String encoding = contentType.substring(start + 8);
        final int end = encoding.indexOf(';');
        if (end >= 0) {
            encoding = encoding.substring(0, end);
        }
        encoding = encoding.trim();
        if ((encoding.length() > 2) && (encoding.startsWith("\"")) && (encoding.endsWith("\""))) {
            encoding = encoding.substring(1, encoding.length() - 1);
        }
        return encoding.trim();
    }

    /**
     * Decodes the specified Set-Cookie HTTP header value into a.
     * @param header header.
     * @return the decoded {@link Cookie}.
     */
    public static Collection<Cookie> decodeCookie(String header) {
        requireNonNull(header, "header");
        final int headerLen = header.length();

        if (headerLen == 0) {
            return Collections.emptySet();
        }

        final List<Cookie> cookies = new ArrayList<>();

        int i = 0;

        boolean rfc2965Style = false;
        if (header.regionMatches(true, 0, "$Version", 0, 8)) {
            // RFC 2965 style cookie, move to after version value
            i = header.indexOf(';') + 1;
            rfc2965Style = true;
        }

        while (true) {
            // Skip spaces and separators.
            while (true) {
                if (i == headerLen) {
                    return cookies;
                }
                final char c = header.charAt(i);
                if (c == '\t' || c == '\n' || c == 0x0b || c == '\f' ||
                    c == '\r' || c == ' ' || c == ',' || c == ';') {
                    i++;
                    continue;
                }
                break;
            }

            final int newNameStart = i;
            int newNameEnd = i;
            String value;

            if (i == headerLen) {
                value = null;
            } else {
                keyValLoop:
                while (true) {
                    final char curChar = header.charAt(i);
                    if (curChar == ';') {
                        // NAME; (no value till ';')
                        newNameEnd = i;
                        value = null;
                        break;
                    } else if (curChar == '=') {
                        // NAME=VALUE
                        newNameEnd = i;
                        i++;
                        if (i == headerLen) {
                            // NAME= (empty value, i.e. nothing after '=')
                            value = "";
                            break;
                        }

                        final int newValueStart = i;
                        char c = header.charAt(i);
                        if (c == '"') {
                            // NAME="VALUE"
                            final StringBuilder newValueBuf = new StringBuilder();

                            final char q = c;
                            boolean hadBackslash = false;
                            i++;
                            while (true) {
                                if (i == headerLen) {
                                    value = newValueBuf.toString();
                                    break keyValLoop;
                                }
                                if (hadBackslash) {
                                    hadBackslash = false;
                                    c = header.charAt(i++);
                                    if (c == '\\' || c == '"') {
                                        // Escape last backslash.
                                        newValueBuf.setCharAt(newValueBuf.length() - 1, c);
                                    } else {
                                        // Do not escape last backslash.
                                        newValueBuf.append(c);
                                    }
                                } else {
                                    c = header.charAt(i++);
                                    if (c == q) {
                                        value = newValueBuf.toString();
                                        break keyValLoop;
                                    }
                                    newValueBuf.append(c);
                                    if (c == '\\') {
                                        hadBackslash = true;
                                    }
                                }
                            }
                        } else {
                            // NAME=VALUE;
                            final int semiPos = header.indexOf(';', i);
                            if (semiPos > 0) {
                                value = header.substring(newValueStart, semiPos);
                                i = semiPos;
                            } else {
                                value = header.substring(newValueStart);
                                i = headerLen;
                            }
                        }
                        break;
                    } else {
                        i++;
                    }

                    if (i == headerLen) {
                        // NAME (no value till the end of string)
                        newNameEnd = headerLen;
                        value = null;
                        break;
                    }
                }
            }

            if (!rfc2965Style || (!header.regionMatches(newNameStart, "$Path", 0, "$Path".length()) &&
                                  !header.regionMatches(newNameStart, "$Domain", 0, "$Domain".length()) &&
                                  !header.regionMatches(newNameStart, "$Port", 0, "$Port".length()))) {

                // skip obsolete RFC2965 fields
                final String name = header.substring(newNameStart, newNameEnd);
                cookies.add(new Cookie(name, value));
            }
        }
    }

    private static int findPathEndIndex(String uri) {
        requireNonNull(uri, "uri");
        final int len = uri.length();
        for (int i = 0; i < len; i++) {
            final char c = uri.charAt(i);
            if (c == '?' || c == '#') {
                return i;
            }
        }
        return len;
    }

    private static void decodeParams(LinkedMultiValueMap<String, String> parameterMap, String s, int from,
                                     Charset charset, int paramsLimit) {
        requireNonNull(parameterMap, "parameterMap");
        requireNonNull(s, "s");
        requireNonNull(charset, "charset");
        checkArgument(from >= 0, "from: %s (expected: >= 0)", from);
        checkArgument(paramsLimit >= 0, "paramsLimit: %s (expected: >= 0)", paramsLimit);
        final int len = s.length();
        if (from >= len) {
            return;
        }
        if (s.charAt(from) == '?') {
            from++;
        }
        int nameStart = from;
        int valueStart = -1;
        int i;
        loop:
        for (i = from; i < len; i++) {
            switch (s.charAt(i)) {
                case '=':
                    if (nameStart == i) {
                        nameStart = i + 1;
                    } else if (valueStart < nameStart) {
                        valueStart = i + 1;
                    }
                    break;
                case '&':
                case ';':
                    if (addParam(s, nameStart, valueStart, i, parameterMap, charset)) {
                        paramsLimit--;
                        if (paramsLimit == 0) {
                            return;
                        }
                    }
                    nameStart = i + 1;
                    break;
                case '#':
                    break loop;
                default:
                    // continue
            }
        }
        addParam(s, nameStart, valueStart, i, parameterMap, charset);
    }

    /**
     * Decode post parameter.
     */
    public static LinkedMultiValueMap decodeBody(LinkedMultiValueMap parammeters, byte[] data,
                                                 String contentType) {
        requireNonNull(parammeters, "parammeters");
        requireNonNull(data, "data");
        requireNonNull(contentType, "contentType");
        try {
            String boundary = "";
            String lastboundary = "";

            int pos = contentType.indexOf("boundary=");

            if (pos != -1) {
                pos += "boundary=".length();
                boundary = "--" + contentType.substring(pos);
                lastboundary = boundary + "--";
            }
            int state = NONE;

            final byte[] b = data;
            final String reqContent = new String(b, "UTF-8");//
            final BufferedReader reqbuf = new BufferedReader(new StringReader(reqContent));

            final String firstLine = reqbuf.readLine();
            parammeters = parseQuery(firstLine, parammeters);
            boolean first = true;
            while (true) {
                String s = "";
                if (first) {
                    s = firstLine;
                    first = false;
                } else {
                    s = reqbuf.readLine();
                }
                if ((s == null) || (s.equals(lastboundary))) {
                    break;
                }

                if (state == NONE && s.startsWith(boundary)) {
                    state = DATAHEADER;
                }
            }
        } catch (Exception ex) {
            logger.error("Parse post parameter failed", ex);
        }
        return parammeters;
    }

    private static LinkedMultiValueMap parseQuery(String query, LinkedMultiValueMap parameters)
            throws UnsupportedEncodingException {
        requireNonNull(query, "query");
        requireNonNull(parameters, "parameters");
        if (query != null) {
            final String[] pairs = query.split("[&]");

            for (String pair : pairs) {
                final String[] param = pair.split("[=]");

                String key = null;
                String value = null;
                if (param.length > 0) {
                    key = URLDecoder.decode(param[0], "utf-8");
                }

                if (param.length > 1) {
                    value = URLDecoder.decode(param[1], "utf-8");
                }

                if (parameters.containsKey(key)) {
                    final Object obj = parameters.get(key);
                    if (obj instanceof List<?>) {
                        final List<String> values = (List<String>) obj;
                        values.add(value);
                    } else if (obj instanceof String) {
                        final List<String> values = new ArrayList<String>();
                        values.add((String) obj);
                        values.add(value);
                        parameters.put(key, values);
                    }
                } else {
                    parameters.add(key, value);
                }
            }
        }
        return parameters;
    }

    private static boolean addParam(String s, int nameStart, int valueStart, int valueEnd,
                                    LinkedMultiValueMap<String, String> parameterMap, Charset charset) {
        requireNonNull(s, "s");
        requireNonNull(parameterMap, "parameterMap");
        requireNonNull(charset, "charset");
        checkArgument(nameStart >= 0, "nameStart: %s (expected: >= 0)", nameStart);
        checkArgument(valueStart >= 0, "valueStart: %s (expected: >= 0)", valueStart);
        checkArgument(valueEnd >= 0, "valueEnd: %s (expected: >= 0)", valueEnd);
        if (nameStart >= valueEnd) {
            return false;
        }
        if (valueStart <= nameStart) {
            valueStart = valueEnd + 1;
        }
        final String name = decodeComponent(s, nameStart, valueStart - 1, charset);
        final String value = decodeComponent(s, valueStart, valueEnd, charset);
        parameterMap.add(name, value);
        return true;
    }

    private static String decodeComponent(String s, int from, int toExcluded, Charset charset) {
        requireNonNull(s, "s");
        requireNonNull(charset, "charset");
        checkArgument(from >= 0, "from: %s (expected: >= 0)", from);
        checkArgument(toExcluded >= 0, "toExcluded: %s (expected: >= 0)", toExcluded);
        final int len = toExcluded - from;
        if (len <= 0) {
            return "";
        }
        int firstEscaped = -1;
        for (int i = from; i < toExcluded; i++) {
            final char c = s.charAt(i);
            if (c == '%' || c == '+') {
                firstEscaped = i;
                break;
            }
        }
        if (firstEscaped == -1) {
            return s.substring(from, toExcluded);
        }

        final CharsetDecoder decoder = CharsetUtil.decoder(charset);

        // Each encoded byte takes 3 characters (e.g. "%20")
        final int decodedCapacity = (toExcluded - firstEscaped) / 3;
        final ByteBuffer byteBuf = ByteBuffer.allocate(decodedCapacity);
        final CharBuffer charBuf = CharBuffer.allocate(decodedCapacity);

        final StringBuilder strBuf = new StringBuilder(len);
        strBuf.append(s, from, firstEscaped);

        for (int i = firstEscaped; i < toExcluded; i++) {
            final char c = s.charAt(i);
            if (c != '%') {
                strBuf.append(c != '+' ? c : SPACE);
                continue;
            }

            byteBuf.clear();
            do {
                if (i + 3 > toExcluded) {
                    throw new IllegalArgumentException("unterminated escape sequence at index " + i +
                                                       " of: " + s);
                }

                byteBuf.put(decodeHexByte(s, i + 1));
                i += 3;
            } while (i < toExcluded && s.charAt(i) == '%');
            i--;

            byteBuf.flip();
            charBuf.clear();
            CoderResult result = decoder.reset().decode(byteBuf, charBuf, true);
            try {
                if (!result.isUnderflow()) {
                    result.throwException();
                }
                result = decoder.flush(charBuf);
                if (!result.isUnderflow()) {
                    result.throwException();
                }
            } catch (CharacterCodingException ex) {
                throw new IllegalStateException(ex);
            }
            strBuf.append(charBuf.flip());
        }
        return strBuf.toString();
    }

    private static byte decodeHexByte(CharSequence s, int pos) {
        requireNonNull(s, "s");
        checkArgument(pos >= 0, "pos: %s (expected: >= 0)", pos);
        final int hi = decodeHexNibble(s.charAt(pos));
        final int lo = decodeHexNibble(s.charAt(pos + 1));
        if (hi == -1 || lo == -1) {
            throw new IllegalArgumentException(String.format(
                    "invalid hex byte '%s' at index %d of '%s'", s.subSequence(pos, pos + 2), pos, s));
        }
        return (byte) ((hi << 4) + lo);
    }

    private static int decodeHexNibble(final char c) {
        // Character.digit() is not used here, as it addresses a larger
        // set of characters (both ASCII and full-width latin letters).
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - ('A' - 0xA);
        }
        if (c >= 'a' && c <= 'f') {
            return c - ('a' - 0xA);
        }
        return -1;
    }

    /**
     * Get server information.
     */
    public static String getServerInfo() {
        return ArmeriaHttpUtil.SERVER_HEADER +
               " (JDK " + SystemInfo.javaVersion() + ";" + SystemInfo.osType().name() + ")";
    }
}
