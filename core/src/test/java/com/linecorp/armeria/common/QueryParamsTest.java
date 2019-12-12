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
 * Copyright 2012 The Netty Project
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import com.google.common.collect.Maps;

class QueryParamsTest {

    @Test
    void testSensitiveParamNames() throws Exception {
        final QueryParams params = QueryParams.of("param1", "value1",
                                                  "param1", "value2",
                                                  "Param1", "Value3",
                                                  "PARAM1", "VALUE4");

        assertThat(params.getAll("param1")).containsExactly("value1", "value2");
        assertThat(params.getAll("Param1")).containsExactly("Value3");
        assertThat(params.getAll("PARAM1")).containsExactly("VALUE4");

        assertThat(params.names())
                .containsExactlyInAnyOrder("param1", "Param1", "PARAM1");
    }

    @Test
    void testInvalidParamName() throws Exception {
        assertThatThrownBy(() -> QueryParams.of(null, "value1"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testSetObject() {
        final String expectedDate = "Mon, 3 Dec 2007 10:15:30 GMT";
        final Instant instant = Instant.parse("2007-12-03T10:15:30.00Z");
        final Date date = new Date(instant.toEpochMilli());
        final Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(instant.toEpochMilli());

        final QueryParamsBuilder params = QueryParams.builder();
        params.setObject("date", date);
        params.setObject("instant", instant);
        params.setObject("calendar", calendar);
        params.setObject("cache-control", ServerCacheControl.DISABLED);
        params.setObject("media-type", MediaType.PLAIN_TEXT_UTF_8);

        assertThat(params.get("date")).isEqualTo(expectedDate);
        assertThat(params.get("instant")).isEqualTo(expectedDate);
        assertThat(params.get("calendar")).isEqualTo(expectedDate);
        assertThat(params.get("cache-control")).isEqualTo("no-cache, no-store, must-revalidate");
        assertThat(params.get("media-type")).isEqualTo("text/plain; charset=utf-8");
    }

    // Tests forked from netty-4.1.43
    // https://github.com/netty/netty/blob/7d6d953153697bd66c3b01ca8ec73c4494a81788/codec-http/src/test/java/io/netty/handler/codec/http/QueryStringDecoderTest.java

    @Test
    void testBasicDecoding() {
        assertThat(QueryParams.fromQueryString(null)).isEmpty();

        assertThat(QueryParams.fromQueryString("a=b=c"))
                .containsExactly(Maps.immutableEntry("a", "b=c"));

        assertThat(QueryParams.fromQueryString("a=1&a=2"))
                .containsExactly(Maps.immutableEntry("a", "1"),
                                 Maps.immutableEntry("a", "2"));

        assertThat(QueryParams.fromQueryString("a=&a=2"))
                .containsExactly(Maps.immutableEntry("a", ""),
                                 Maps.immutableEntry("a", "2"));

        assertThat(QueryParams.fromQueryString("a=1&a="))
                .containsExactly(Maps.immutableEntry("a", "1"),
                                 Maps.immutableEntry("a", ""));

        assertThat(QueryParams.fromQueryString("a=1&a=&a="))
                .containsExactly(Maps.immutableEntry("a", "1"),
                                 Maps.immutableEntry("a", ""),
                                 Maps.immutableEntry("a", ""));

        assertThat(QueryParams.fromQueryString("a=1=&a==2"))
                .containsExactly(Maps.immutableEntry("a", "1="),
                                 Maps.immutableEntry("a", "=2"));

        assertThat(QueryParams.fromQueryString("abc=1%2023&abc=124%20"))
                .containsExactly(Maps.immutableEntry("abc", "1 23"),
                                 Maps.immutableEntry("abc", "124 "));

        assertThat(QueryParams.fromQueryString("a+b=c+d"))
                .containsExactly(Maps.immutableEntry("a b", "c d"));
    }

    @Test
    void testExoticDecoding() {
        assertQueryString("", "");
        assertQueryString("", "?");
        assertQueryString("", "??");
        assertQueryString("a=", "a");
        assertQueryString("a=", "a&");
        assertQueryString("a=", "&a");
        assertQueryString("a=", "&a&");
        assertQueryString("a=", "&=a");
        assertQueryString("a=", "=a&");
        assertQueryString("a=", "a=&");
        assertQueryString("a=b&c=d", "a=b&&c=d");
        assertQueryString("a=b&c=d", "a=b&=&c=d");
        assertQueryString("a=b&c=d", "a=b&==&c=d");
        assertQueryString("a=b&c=&x=y", "a=b&c&x=y");
        assertQueryString("a=", "a=");
        assertQueryString("a=", "&a=");
        assertQueryString("a=b&c=d", "a=b&c=d");
        assertQueryString("a=1&a=&a=", "a=1&a&a=");
    }

    @Test
    void testSemicolonDecoding() {
        assertQueryString("/foo?a=1;2", "/foo?a=1;2", true);
        // ";" should be treated as a normal character, see #8855
        assertQueryString("/foo?a=1;2", "/foo?a=1%3B2", false);
    }

    @Test
    void testFragmentDecoding() {
        // a 'fragment' after '#' should be cut (see RFC 3986)
        assertThat(QueryParams.fromQueryString("#123")).isEmpty();
        assertThat(QueryParams.fromQueryString("?a#anchor"))
                .containsExactly(Maps.immutableEntry("a", ""));
        assertThat(QueryParams.fromQueryString("#a#b?c=d")).isEmpty();
        assertThat(QueryParams.fromQueryString("?#")).isEmpty();
        assertThat(QueryParams.fromQueryString("?#anchor")).isEmpty();
        assertThat(QueryParams.fromQueryString("?#a=b#anchor")).isEmpty();
    }

    @Test
    void testHashDos() {
        final StringBuilder buf = new StringBuilder();
        buf.append('?');
        for (int i = 0; i < 65536; i++) {
            buf.append('k');
            buf.append(i);
            buf.append("=v");
            buf.append(i);
            buf.append('&');
        }
        assertThat(QueryParams.fromQueryString(buf.toString(), 100)).hasSize(100);
    }

    @Test
    void testUrlDecoding() throws Exception {
        final String caffe = new String(
                // "Caffé" but instead of putting the literal E-acute in the
                // source file, we directly use the UTF-8 encoding so as to
                // not rely on the platform's default encoding (not portable).
                new byte[] {'C', 'a', 'f', 'f', (byte) 0xC3, (byte) 0xA9},
                StandardCharsets.UTF_8);
        final String[] tests = {
                // Encoded   ->   Decoded or error message substring
                "",               "",
                "foo",            "foo",
                "f+o",            "f o",
                "f++",            "f  ",
                "fo%",            "unterminated escape sequence at index 2 of: fo%",
                "%42",            "B",
                "%5f",            "_",
                "f%4",            "unterminated escape sequence at index 1 of: f%4",
                "%x2",            "invalid hex byte 'x2' at index 1 of '%x2'",
                "%4x",            "invalid hex byte '4x' at index 1 of '%4x'",
                "Caff%C3%A9",     caffe,
                "случайный праздник",               "случайный праздник",
                "случайный%20праздник",             "случайный праздник",
                "случайный%20праздник%20%E2%98%BA", "случайный праздник ☺",
                };
        for (int i = 0; i < tests.length; i += 2) {
            final String encoded = tests[i];
            final String expected = tests[i + 1];
            try {
                final String decoded = QueryStringDecoder.decodeComponent(encoded, 0, encoded.length());
                assertThat(decoded).isEqualTo(expected);
            } catch (IllegalArgumentException e) {
                assertThat(e.getMessage()).contains(expected);
            }
        }
    }

    private static void assertQueryString(String expected, String actual) {
        assertQueryString(expected, actual, false);
    }

    private static void assertQueryString(String expected, String actual, boolean semicolonIsSeparator) {
        assertThat(QueryParams.fromQueryString(actual, semicolonIsSeparator))
                .isEqualTo(QueryParams.fromQueryString(expected, semicolonIsSeparator));
    }

    // Tests forked from netty-4.1.43
    // https://github.com/netty/netty/blob/dcd322dda2dfd1e0567017d2e02c53728c310032/codec-http/src/test/java/io/netty/handler/codec/http/QueryStringEncoderTest.java

    @Test
    @SuppressWarnings("checkstyle:AvoidEscapedUnicodeCharacters")
    void testDefaultEncoding() throws Exception {
        assertThat(QueryParams.of("a", "b=c").toQueryString()).isEqualTo("a=b%3Dc");
        assertThat(QueryParams.of("a", "\u00A5").toQueryString()).isEqualTo("a=%C2%A5");
        assertThat(QueryParams.of("a", "1", "b", "2").toQueryString()).isEqualTo("a=1&b=2");
        assertThat(QueryParams.of("a", "", "b", "").toQueryString()).isEqualTo("a=&b=");
    }

    @Test
    void testWhitespaceEncoding() throws Exception {
        assertThat(QueryParams.of("a", "b c").toQueryString()).isEqualTo("a=b%20c");
    }
}
