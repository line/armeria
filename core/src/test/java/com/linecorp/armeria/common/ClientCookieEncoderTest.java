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

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableSet;

class ClientCookieEncoderTest {

    // Forked from netty-4.1.34.
    // https://github.com/netty/netty/blob/4978266d52a90252ae00b40894a4398292830d7f/codec-http/src/test/java/io/netty/handler/codec/http/cookie/ClientCookieEncoderTest.java

    @Test
    public void testEncodingWithNoCookies() {
        assertThatThrownBy(() -> Cookie.toCookieHeader(new Cookie[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> Cookie.toCookieHeader(ImmutableSet.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void testEncodingMultipleClientCookies() {
        final String c1 = "myCookie=myValue";
        final String c2 = "myCookie2=myValue2";
        final String c3 = "myCookie3=myValue3";
        final Cookie cookie1 = Cookie.secureBuilder("myCookie", "myValue")
                                     .domain(".adomainsomewhere")
                                     .maxAge(50)
                                     .path("/apathsomewhere")
                                     .sameSite("Strict")
                                     .build();
        final Cookie cookie2 = Cookie.builder("myCookie2", "myValue2")
                                     .domain(".anotherdomainsomewhere")
                                     .path("/anotherpathsomewhere")
                                     .build();
        final Cookie cookie3 = Cookie.of("myCookie3", "myValue3");
        final String encodedCookie = Cookie.toCookieHeader(cookie1, cookie2, cookie3);
        // Cookies should be sorted into decreasing order of path length, as per RFC 6265.
        // When no path is provided, we assume maximum path length (so cookie3 comes first).
        assertThat(encodedCookie).isEqualTo(c3 + "; " + c2 + "; " + c1);
    }

    @Test
    void testWrappedCookieValue() {
        assertThat(Cookie.of("myCookie", "\"foo\"").toCookieHeader()).isEqualTo("myCookie=\"foo\"");
    }

    @Test
    void testRejectCookieValueWithSemicolon() {
        assertThatThrownBy(() -> Cookie.of("myCookie", "foo;bar").toCookieHeader())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cookie value contains an invalid char: ;");
    }
}
