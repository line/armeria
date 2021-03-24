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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

import io.netty.handler.codec.http.cookie.CookieHeaderNames;

/**
 * A <a href="https://datatracker.ietf.org/doc/rfc6265/">RFC 6265</a> compliant cookie decoder for server side.
 *
 * <p>Thie decoder decodes only cookie name and value. The old fields in
 * <a href="https://datatracker.ietf.org/doc/rfc2965/">RFC 2965</a> such as {@code "path"} and {@code "domain"} are
 * ignored.</p>
 *
 * @see ServerCookieEncoder
 */
final class ServerCookieDecoder {

    // Forked from netty-4.1.43
    // https://github.com/netty/netty/blob/ba95c401a7cf8c7923fce660e16c8ba567d62f30/codec-http/src/main/java/io/netty/handler/codec/http/cookie/ServerCookieDecoder.java

    private static final Logger logger = LoggerFactory.getLogger(ServerCookieDecoder.class);

    private static final String RFC2965_VERSION = "$Version";

    private static final String RFC2965_PATH = '$' + CookieHeaderNames.PATH;

    private static final String RFC2965_DOMAIN = '$' + CookieHeaderNames.DOMAIN;

    private static final String RFC2965_PORT = "$Port";

    /**
     * Decodes the specified {@code "Cookie"} header value into {@link Cookie}s.
     *
     * @param strict whether to validate that name and value chars are in the valid scope defined in RFC 6265.
     * @return the decoded {@link Cookie}s.
     */
    static Cookies decode(boolean strict, String header) {
        final int headerLen = header.length();
        final ImmutableSet.Builder<Cookie> cookies = ImmutableSet.builder();

        int i = 0;

        boolean rfc2965Style = false;
        if (header.regionMatches(true, 0, RFC2965_VERSION, 0, RFC2965_VERSION.length())) {
            // RFC 2965 style cookie, move to after version value
            i = header.indexOf(';') + 1;
            rfc2965Style = true;
        }

        loop: for (;;) {

            // Skip spaces and separators.
            for (;;) {
                if (i == headerLen) {
                    break loop;
                }
                final char c = header.charAt(i);
                if (c == '\t' || c == '\n' || c == 0x0b || c == '\f' ||
                    c == '\r' || c == ' ' || c == ',' || c == ';') {
                    i++;
                    continue;
                }
                break;
            }

            final int nameBegin = i;
            final int nameEnd;
            final int valueBegin;
            final int valueEnd;

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

            if (rfc2965Style && (header.regionMatches(nameBegin, RFC2965_PATH, 0, RFC2965_PATH.length()) ||
                    header.regionMatches(nameBegin, RFC2965_DOMAIN, 0, RFC2965_DOMAIN.length()) ||
                    header.regionMatches(nameBegin, RFC2965_PORT, 0, RFC2965_PORT.length()))) {

                // skip obsolete RFC2965 fields
                continue;
            }

            final CookieBuilder builder = initCookie(logger, strict,
                                                     header, nameBegin, nameEnd, valueBegin, valueEnd);
            if (builder != null) {
                cookies.add(builder.build());
            }
        }

        return Cookies.of(cookies.build());
    }

    private ServerCookieDecoder() {}
}
