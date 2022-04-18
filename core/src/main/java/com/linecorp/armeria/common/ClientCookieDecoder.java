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
 * Copyright 2015 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.CookieUtil.initCookie;

import java.util.Arrays;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.handler.codec.DateFormatter;
import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite;

/**
 * A <a href="https://datatracker.ietf.org/doc/rfc6265/">RFC 6265</a> compliant cookie decoder for client side.
 *
 * <p>It will store the way the raw value was wrapped in {@link Cookie#isValueQuoted()} so it can be sent back
 * to the origin server as is.</p>
 *
 * @see ClientCookieEncoder
 */
final class ClientCookieDecoder {

    // Forked from netty-4.1.43
    // https://github.com/netty/netty/blob/587afddb279bea3fd0f64d3421de8e69a35cecb9/codec-http/src/main/java/io/netty/handler/codec/http/cookie/ClientCookieDecoder.java

    private static final Logger logger = LoggerFactory.getLogger(ClientCookieDecoder.class);

    /**
     * Decodes the specified {@code "Set-Cookie"} header value into a {@link Cookie}.
     *
     * @param strict whether to validate the name and value chars are in the valid scope defined in RFC 6265.
     * @return the decoded {@link Cookie}, or {@code null} if malformed.
     */
    @Nullable
    static Cookie decode(boolean strict, String header) {
        final int headerLen = header.length();
        assert headerLen != 0 : headerLen;

        CookieBuilder builder = null;

        loop: for (int i = 0;;) {

            // Skip spaces and separators.
            for (;;) {
                if (i == headerLen) {
                    break loop;
                }
                final char c = header.charAt(i);
                if (c == ',') {
                    // Having multiple cookies in a single Set-Cookie header is
                    // deprecated, modern browsers only parse the first one
                    break loop;
                }

                if (c == '\t' || c == '\n' || c == 0x0b || c == '\f' ||
                    c == '\r' || c == ' ' || c == ';') {
                    i++;
                    continue;
                }
                break;
            }

            final int nameBegin = i;
            final int nameEnd;
            final int valueBegin;
            int valueEnd;

            for (;;) {
                final char curChar = header.charAt(i);
                if (curChar == ';') {
                    // NAME; (no value till ';')
                    nameEnd = i;
                    valueBegin = valueEnd = -1;
                    break;
                }

                if (curChar == '=') {
                    // NAME=VALUE
                    nameEnd = i;
                    i++;
                    if (i == headerLen) {
                        // NAME= (empty value, i.e. nothing after '=')
                        valueBegin = valueEnd = 0;
                        break;
                    }

                    valueBegin = i;
                    // NAME=VALUE;
                    final int semiPos = header.indexOf(';', i);
                    valueEnd = i = semiPos > 0 ? semiPos : headerLen;
                    break;
                }

                i++;

                if (i == headerLen) {
                    // NAME (no value till the end of string)
                    nameEnd = headerLen;
                    valueBegin = valueEnd = -1;
                    break;
                }
            }

            if (valueEnd > 0 && header.charAt(valueEnd - 1) == ',') {
                // old multiple cookies separator, skipping it
                valueEnd--;
            }

            if (builder == null) {
                // cookie name-value pair
                builder = initCookie(logger, strict, header, nameBegin, nameEnd, valueBegin, valueEnd);
                if (builder == null) {
                    return null;
                }
            } else {
                // cookie attribute
                appendAttribute(builder, header, nameBegin, nameEnd, valueBegin, valueEnd);
            }
        }

        if (builder == null) {
            return null;
        }

        mergeMaxAgeAndExpires(builder, header);
        return builder.build();
    }

    /**
     * Parse and store a key-value pair. First one is considered to be the
     * cookie name/value. Unknown attribute names are silently discarded.
     *
     * @param keyStart
     *            where the key starts in the header
     * @param keyEnd
     *            where the key ends in the header
     * @param valueStart
     *            where the value starts in the header
     * @param valueEnd
     *            where the value ends in the header
     */
    private static void appendAttribute(CookieBuilder builder, String header,
                                        int keyStart, int keyEnd, int valueStart, int valueEnd) {
        final int length = keyEnd - keyStart;

        if (length == 4) {
            parse4(builder, header, keyStart, valueStart, valueEnd);
        } else if (length == 6) {
            parse6(builder, header, keyStart, valueStart, valueEnd);
        } else if (length == 7) {
            parse7(builder, header, keyStart, valueStart, valueEnd);
        } else if (length == 8) {
            parse8(builder, header, keyStart, valueStart, valueEnd);
        }
    }

    private static void parse4(CookieBuilder builder, String header,
                               int nameStart, int valueStart, int valueEnd) {
        if (header.regionMatches(true, nameStart, CookieHeaderNames.PATH, 0, 4)) {
            final String path = computeValue(header, valueStart, valueEnd);
            if (path != null) {
                builder.path(path);
            }
        }
    }

    private static void parse6(CookieBuilder builder, String header,
                               int nameStart, int valueStart, int valueEnd) {
        if (header.regionMatches(true, nameStart, CookieHeaderNames.DOMAIN, 0, 5)) {
            final String domain = computeValue(header, valueStart, valueEnd);
            if (domain != null) {
                builder.domain(domain);
            }
        } else if (header.regionMatches(true, nameStart, CookieHeaderNames.SECURE, 0, 5)) {
            builder.secure(true);
        }
    }

    private static void setMaxAge(CookieBuilder builder, String value) {
        try {
            builder.maxAge(Math.max(Long.parseLong(value), 0L));
        } catch (NumberFormatException e1) {
            // ignore failure to parse -> treat as session cookie
        }
    }

    private static void parse7(CookieBuilder builder, String header,
                               int nameStart, int valueStart, int valueEnd) {
        if (header.regionMatches(true, nameStart, CookieHeaderNames.EXPIRES, 0, 7)) {
            builder.expiresStart = valueStart;
            builder.expiresEnd = valueEnd;
        } else if (header.regionMatches(true, nameStart, CookieHeaderNames.MAX_AGE, 0, 7)) {
            final String maxAge = computeValue(header, valueStart, valueEnd);
            if (maxAge != null) {
                setMaxAge(builder, maxAge);
            }
        }
    }

    private static void parse8(CookieBuilder builder, String header,
                               int nameStart, int valueStart, int valueEnd) {
        if (header.regionMatches(true, nameStart, CookieHeaderNames.HTTPONLY, 0, 8)) {
            builder.httpOnly(true);
        } else if (header.regionMatches(true, nameStart, CookieHeaderNames.SAMESITE, 0, 8)) {
            final String sameSite = computeValue(header, valueStart, valueEnd);
            builder.sameSite(getValidSameSite(sameSite));
        }
    }

    private static boolean isValueDefined(int valueStart, int valueEnd) {
        return valueStart != -1 && valueStart != valueEnd;
    }

    @Nullable
    private static String computeValue(String header, int valueStart, int valueEnd) {
        return isValueDefined(valueStart, valueEnd) ? header.substring(valueStart, valueEnd) : null;
    }

    private static void mergeMaxAgeAndExpires(CookieBuilder builder, String header) {
        // max age has precedence over expires
        if (builder.maxAge != Cookie.UNDEFINED_MAX_AGE) {
            return;
        }

        if (isValueDefined(builder.expiresStart, builder.expiresEnd)) {
            final Date expiresDate =
                    DateFormatter.parseHttpDate(header, builder.expiresStart, builder.expiresEnd);
            if (expiresDate != null) {
                final long maxAgeMillis = expiresDate.getTime() - System.currentTimeMillis();
                builder.maxAge(maxAgeMillis / 1000 + (maxAgeMillis % 1000 != 0 ? 1 : 0));
            }
        }
    }

    /**
     * Returns a valid {@code "SameSite"} attribute.
     * This method returns {@code "Lax"} as default if the attribute is empty or invalid value.
     */
    private static String getValidSameSite(@Nullable String sameSite) {
        return Arrays.stream(SameSite.values())
                     .map(SameSite::name)
                     .filter(name -> name.equalsIgnoreCase(sameSite))
                     .findFirst()
                     .orElse(SameSite.Lax.name());
    }

    private ClientCookieDecoder() {}
}
