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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.ParseException;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

import io.netty.handler.codec.DateFormatter;

public class ServerCookieEncoderTest {

    // Forked from netty-4.1.34.
    // https://github.com/netty/netty/blob/4c709be1abf6e52c6a5640c1672d259f1de638d1/codec-http/src/test/java/io/netty/handler/codec/http/cookie/ServerCookieEncoderTest.java

    @Test
    public void testEncodingSingleCookieV0() throws ParseException {

        final int maxAge = 50;

        final String result = "myCookie=myValue; Max-Age=50; Expires=(.+?); Path=/apathsomewhere; " +
                              "Domain=adomainsomewhere; Secure; SameSite=Strict";
        final Cookie cookie = Cookie.builder("myCookie", "myValue")
                                    .domain(".adomainsomewhere")
                                    .maxAge(maxAge)
                                    .path("/apathsomewhere")
                                    .secure(true)
                                    .sameSite("Strict")
                                    .build();

        final String encodedCookie = cookie.toSetCookieHeader();

        final Matcher matcher = Pattern.compile(result).matcher(encodedCookie);
        assertThat(matcher.find()).isTrue();
        final Date expiresDate = DateFormatter.parseHttpDate(matcher.group(1));
        final long diff = (expiresDate.getTime() - System.currentTimeMillis()) / 1000;
        // 2 secs should be fine
        assertThat(Math.abs(diff - maxAge)).isLessThanOrEqualTo(2);
    }

    @Test
    public void testEncodingWithNoCookies() {
        assertThat(Cookie.toSetCookieHeaders()).isEmpty();
        assertThat(Cookie.toSetCookieHeaders(ImmutableSet.of())).isEmpty();
    }

    @Test
    public void testEncodingMultipleCookies() {
        final Cookie cookie1 = Cookie.of("cookie1", "value1");
        final Cookie cookie2 = Cookie.of("cookie2", "value2");
        final Cookie cookie3 = Cookie.of("cookie1", "value3");
        final List<String> encodedCookies = Cookie.toSetCookieHeaders(cookie1, cookie2, cookie3);
        assertThat(encodedCookies).containsExactly("cookie1=value1", "cookie2=value2", "cookie1=value3");
    }

    @Test
    public void illegalCharInCookieNameMakesStrictEncoderThrowsException() {
        final Set<Character> illegalChars = new HashSet<Character>();
        // CTLs
        for (int i = 0x00; i <= 0x1F; i++) {
            illegalChars.add((char) i);
        }
        illegalChars.add((char) 0x7F);
        // separators
        for (char c : new char[] { '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']',
                '?', '=', '{', '}', ' ', '\t' }) {
            illegalChars.add(c);
        }

        int exceptions = 0;

        for (char c : illegalChars) {
            try {
                Cookie.of("foo" + c + "bar", "value").toSetCookieHeader();
            } catch (IllegalArgumentException e) {
                exceptions++;
            }
        }

        assertThat(exceptions).isEqualTo(illegalChars.size());
    }

    @Test
    public void illegalCharInCookieValueMakesStrictEncoderThrowsException() {
        final Set<Character> illegalChars = new HashSet<Character>();
        // CTLs
        for (int i = 0x00; i <= 0x1F; i++) {
            illegalChars.add((char) i);
        }
        illegalChars.add((char) 0x7F);
        // whitespace, DQUOTE, comma, semicolon, and backslash
        for (char c : new char[] { ' ', '"', ',', ';', '\\' }) {
            illegalChars.add(c);
        }

        int exceptions = 0;

        for (char c : illegalChars) {
            try {
                Cookie.of("name", "value" + c).toSetCookieHeader();
            } catch (IllegalArgumentException e) {
                exceptions++;
            }
        }

        assertThat(exceptions).isEqualTo(illegalChars.size());
    }

    @Test
    public void illegalCharInWrappedValueAppearsInException() {
        assertThatThrownBy(() -> Cookie.of("name", "\"value,\"").toSetCookieHeader())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cookie value contains an invalid char: ,");
    }

    @Test
    public void testEncodingMultipleCookiesLax() {
        final Cookie cookie1 = Cookie.of("cookie1", "value1");
        final Cookie cookie2 = Cookie.of("cookie2", "value2");
        final Cookie cookie3 = Cookie.of("cookie1", "value3");
        final List<String> encodedCookies = Cookie.toSetCookieHeaders(cookie1, cookie2, cookie3);
        assertThat(encodedCookies).containsExactly("cookie1=value1", "cookie2=value2", "cookie1=value3");
    }
}
