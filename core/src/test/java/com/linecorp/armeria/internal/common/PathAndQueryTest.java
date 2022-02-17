/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.internal.common.PathAndQuery.decodePercentEncodedQuery;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.annotation.Nullable;

class PathAndQueryTest {

    private static final Logger logger = LoggerFactory.getLogger(PathAndQueryTest.class);

    private static final Set<String> QUERY_SEPARATORS = ImmutableSet.of("&", ";");

    private static final Set<String> BAD_DOUBLE_DOT_PATTERNS = ImmutableSet.of(
            "..", "/..", "../", "/../",
            "../foo", "/../foo",
            "foo/..", "/foo/..",
            "foo/../", "/foo/../",
            "foo/../bar", "/foo/../bar",

            // Dots escaped
            ".%2e", "/.%2e", "%2E./", "/%2E./", ".%2E/", "/.%2E/",
            "foo/.%2e", "/foo/.%2e",
            "foo/%2E./", "/foo/%2E./",
            "foo/%2E./bar", "/foo/%2E./bar",

            // Slashes escaped
            "%2f..", "..%2F", "/..%2F", "%2F../", "%2f..%2f",
            "/foo%2f..", "/foo%2f../", "/foo/..%2f", "/foo%2F..%2F",

            // Dots and slashes escaped
            ".%2E%2F"
    );

    private static final Set<String> GOOD_DOUBLE_DOT_PATTERNS = ImmutableSet.of(
            "..a", "a..", "a..b",
            "/..a", "/a..", "/a..b",
            "..a/", "a../", "a..b/",
            "/..a/", "/a../", "/a..b/"
    );

    @Test
    void empty() {
        final PathAndQuery res = parse(null);
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/");
        assertThat(res.query()).isNull();

        final PathAndQuery res2 = parse("");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/");
        assertThat(res2.query()).isNull();

        final PathAndQuery res3 = parse("?");
        assertThat(res3).isNotNull();
        assertThat(res3.path()).isEqualTo("/");
        assertThat(res3.query()).isEqualTo("");
    }

    @Test
    void relative() {
        assertThat(parse("foo")).isNull();
    }

    @Test
    void doubleDotsInPath() {
        BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> assertProhibited(pattern));
        GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            final String path = pattern.startsWith("/") ? pattern : '/' + pattern;
            final PathAndQuery res = parse(path);
            assertThat(res).as("Ensure %s is allowed.", path).isNotNull();
            assertThat(res.path()).as("Ensure %s is parsed as-is.", path).isEqualTo(path);
        });
    }

    @Test
    void doubleDotsInFreeFormQuery() {
        BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            assertProhibited("/?" + pattern);
        });

        GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            assertQueryStringAllowed("/?" + pattern, pattern);
        });
    }

    @Test
    void prohibitDoubleDotsInNameValueQuery() {
        // Dots in a query param name.
        BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            assertProhibited("/?" + pattern + "=foo");
        });
        GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            assertQueryStringAllowed("/?" + pattern + "=foo");
        });

        // Dots in a query param value.
        BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            assertProhibited("/?foo=" + pattern);
        });
        GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            assertQueryStringAllowed("/?foo=" + pattern);
        });

        QUERY_SEPARATORS.forEach(qs -> {
            // Dots in the second query param name.
            BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertProhibited("/?a=b" + qs + pattern + "=c");
            });
            GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertQueryStringAllowed("/?a=b" + qs + pattern + "=c");
            });

            // Dots in the second query param value.
            BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertProhibited("/?a=b" + qs + "c=" + pattern);
            });
            GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertQueryStringAllowed("/?a=b" + qs + "c=" + pattern);
            });

            // Dots in the name of the query param in the middle.
            BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertProhibited("/?a=b" + qs + pattern + "=c" + qs + "d=e");
            });
            GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertQueryStringAllowed("/?a=b" + qs + pattern + "=c" + qs + "d=e");
            });

            // Dots in the value of the query param in the middle.
            BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertProhibited("/?a=b" + qs + "c=" + pattern + qs + "d=e");
            });
            GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertQueryStringAllowed("/?a=b" + qs + "c=" + pattern + qs + "d=e");
            });
        });
    }

    @Test
    void allowDoubleDotsInNameValueQuery() {
        BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            assertQueryStringAllowed("/?" + pattern, decodePercentEncodedQuery(pattern), true);
        });

        GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            assertQueryStringAllowed("/?" + pattern, pattern, true);
        });

        // Dots in a query param name.
        BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            final String query = pattern + "=foo";
            assertQueryStringAllowed("/?" + query, decodePercentEncodedQuery(query), true);
        });
        GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            assertQueryStringAllowed("/?" + pattern + "=foo", true);
        });

        // Dots in a query param value.
        BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            final String query = "foo=" + pattern;
            assertQueryStringAllowed("/?" + query, decodePercentEncodedQuery(query), true);
        });
        GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
            assertQueryStringAllowed("/?foo=" + pattern, true);
        });

        QUERY_SEPARATORS.forEach(qs -> {
            // Dots in the second query param name.
            BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                final String query = "a=b" + qs + pattern + "=c";
                assertQueryStringAllowed("/?" + query, decodePercentEncodedQuery(query), true);
            });
            GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertQueryStringAllowed("/?a=b" + qs + pattern + "=c", true);
            });

            // Dots in the second query param value.
            BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                final String query = "a=b" + qs + "c=" + pattern;
                assertQueryStringAllowed("/?" + query, decodePercentEncodedQuery(query), true);
            });
            GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertQueryStringAllowed("/?a=b" + qs + "c=" + pattern, true);
            });

            // Dots in the name of the query param in the middle.
            BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                final String query = "a=b" + qs + pattern + "=c" + qs + "d=e";
                assertQueryStringAllowed("/?" + query, decodePercentEncodedQuery(query), true);
            });
            GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertQueryStringAllowed("/?a=b" + qs + pattern + "=c" + qs + "d=e");
            });

            // Dots in the value of the query param in the middle.
            BAD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                final String query = "a=b" + qs + "c=" + pattern + qs + "d=e";
                assertQueryStringAllowed("/?" + query, decodePercentEncodedQuery(query), true);
            });
            GOOD_DOUBLE_DOT_PATTERNS.forEach(pattern -> {
                assertQueryStringAllowed("/?a=b" + qs + "c=" + pattern + qs + "d=e", true);
            });
        });
    }


    /**
     * {@link PathAndQuery} treats the first `=` in a query parameter as `/` internally to simplify
     * the detection the logic. This test makes sure the `=` appeared later is not treated as `/`.
     */
    @Test
    void dotsAndEqualsInNameValueQuery() {
        QUERY_SEPARATORS.forEach(qs -> {
            final PathAndQuery res = parse("/?a=..=" + qs + "b=..=");
            assertThat(res).isNotNull();
            assertThat(res.query()).isEqualTo("a=..=" + qs + "b=..=");
            assertThat(QueryParams.fromQueryString(res.query(), true)).containsExactly(
                    Maps.immutableEntry("a", "..="),
                    Maps.immutableEntry("b", "..=")
            );

            final PathAndQuery res2 = parse("/?a==.." + qs + "b==..");
            assertThat(res2).isNotNull();
            assertThat(res2.query()).isEqualTo("a==.." + qs + "b==..");
            assertThat(QueryParams.fromQueryString(res2.query(), true)).containsExactly(
                    Maps.immutableEntry("a", "=.."),
                    Maps.immutableEntry("b", "=..")
            );

            final PathAndQuery res3 = parse("/?a==..=" + qs + "b==..=");
            assertThat(res3).isNotNull();
            assertThat(res3.query()).isEqualTo("a==..=" + qs + "b==..=");
            assertThat(QueryParams.fromQueryString(res3.query(), true)).containsExactly(
                    Maps.immutableEntry("a", "=..="),
                    Maps.immutableEntry("b", "=..=")
            );
        });
    }

    @Test
    void hexadecimal() {
        assertThat(parse("/%")).isNull();
        assertThat(parse("/%0")).isNull();
        assertThat(parse("/%0X")).isNull();
        assertThat(parse("/%X0")).isNull();
    }

    @Test
    void controlChars() {
        assertThat(parse("/\0")).isNull();
        assertThat(parse("/a\nb")).isNull();
        assertThat(parse("/a\u007fb")).isNull();

        // Escaped
        assertThat(parse("/%00")).isNull();
        assertThat(parse("/a%09b")).isNull();
        assertThat(parse("/a%0ab")).isNull();
        assertThat(parse("/a%0db")).isNull();
        assertThat(parse("/a%7fb")).isNull();

        // With query string
        assertThat(parse("/\0?c")).isNull();
        assertThat(parse("/a\tb?c")).isNull();
        assertThat(parse("/a\nb?c")).isNull();
        assertThat(parse("/a\rb?c")).isNull();
        assertThat(parse("/a\u007fb?c")).isNull();

        // With query string with control chars
        assertThat(parse("/?\0")).isNull();
        assertThat(parse("/?%00")).isNull();
        assertThat(parse("/?a\u007fb")).isNull();
        assertThat(parse("/?a%7Fb")).isNull();
        // However, 0x0A, 0x0D, 0x09 should be accepted in a query string.
        assertThat(parse("/?a\tb").query()).isEqualTo("a%09b");
        assertThat(parse("/?a\nb").query()).isEqualTo("a%0Ab");
        assertThat(parse("/?a\rb").query()).isEqualTo("a%0Db");
        assertThat(parse("/?a%09b").query()).isEqualTo("a%09b");
        assertThat(parse("/?a%0Ab").query()).isEqualTo("a%0Ab");
        assertThat(parse("/?a%0Db").query()).isEqualTo("a%0Db");
    }

    @Test
    void percent() {
        final PathAndQuery res = parse("/%25");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/%25");
        assertThat(res.query()).isNull();
    }

    @Test
    void shouldNotDecodeSlash() {
        final PathAndQuery res = parse("%2F?%2F");
        // Do not accept a relative path.
        assertThat(res).isNull();
        final PathAndQuery res1 = parse("/%2F?%2F");
        assertThat(res1).isNotNull();
        assertThat(res1.path()).isEqualTo("/%2F");
        assertThat(res1.query()).isEqualTo("%2F");

        final PathAndQuery pathOnly = parse("/foo%2F");
        assertThat(pathOnly).isNotNull();
        assertThat(pathOnly.path()).isEqualTo("/foo%2F");
        assertThat(pathOnly.query()).isNull();

        final PathAndQuery queryOnly = parse("/?%2f=%2F");
        assertThat(queryOnly).isNotNull();
        assertThat(queryOnly.path()).isEqualTo("/");
        assertThat(queryOnly.query()).isEqualTo("%2F=%2F");
    }

    @Test
    void consecutiveSlashes() {
        final PathAndQuery res = parse(
                "/path//with///consecutive////slashes?/query//with///consecutive////slashes");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/path/with/consecutive/slashes");
        assertThat(res.query()).isEqualTo("/query//with///consecutive////slashes");

        // Encoded slashes
        final PathAndQuery res2 = parse(
                "/path%2F/with/%2F/consecutive//%2F%2Fslashes?/query%2F/with/%2F/consecutive//%2F%2Fslashes");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/path%2F/with/%2F/consecutive/%2F%2Fslashes");
        assertThat(res2.query()).isEqualTo("/query%2F/with/%2F/consecutive//%2F%2Fslashes");
    }

    @Test
    void colon() {
        assertThat(parse("/:")).isNotNull();
        assertThat(parse("/:/")).isNotNull();
        assertThat(parse("/a/:")).isNotNull();
        assertThat(parse("/a/:/")).isNotNull();
    }

    @Test
    void rawUnicode() {
        // 2- and 3-byte UTF-8
        final PathAndQuery res1 = parse("/\u00A2?\u20AC"); // ¢ and €
        assertThat(res1).isNotNull();
        assertThat(res1.path()).isEqualTo("/%C2%A2");
        assertThat(res1.query()).isEqualTo("%E2%82%AC");

        // 4-byte UTF-8
        final PathAndQuery res2 = parse("/\uD800\uDF48"); // 𐍈
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/%F0%90%8D%88");
        assertThat(res2.query()).isNull();

        // 5- and 6-byte forms are only theoretically possible, so we won't test them here.
    }

    @Test
    void encodedUnicode() {
        final String encodedPath = "/%ec%95%88";
        final String encodedQuery = "%eb%85%95";
        final PathAndQuery res = parse(encodedPath + '?' + encodedQuery);
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo(Ascii.toUpperCase(encodedPath));
        assertThat(res.query()).isEqualTo(Ascii.toUpperCase(encodedQuery));
    }

    @Test
    void noEncoding() {
        final PathAndQuery res = parse("/a?b=c");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/a");
        assertThat(res.query()).isEqualTo("b=c");
    }

    @Test
    void space() {
        final PathAndQuery res = parse("/ ? ");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/%20");
        assertThat(res.query()).isEqualTo("+");

        final PathAndQuery res2 = parse("/%20?%20");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/%20");
        assertThat(res2.query()).isEqualTo("+");
    }

    @Test
    void plus() {
        final PathAndQuery res = parse("/+?a+b=c+d");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/+");
        assertThat(res.query()).isEqualTo("a+b=c+d");

        final PathAndQuery res2 = parse("/%2b?a%2bb=c%2bd");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/+");
        assertThat(res2.query()).isEqualTo("a%2Bb=c%2Bd");
    }

    @Test
    void ampersand() {
        final PathAndQuery res = parse("/&?a=1&a=2&b=3");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/&");
        assertThat(res.query()).isEqualTo("a=1&a=2&b=3");

        // '%26' in a query string should never be decoded into '&'.
        final PathAndQuery res2 = parse("/%26?a=1%26a=2&b=3");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/&");
        assertThat(res2.query()).isEqualTo("a=1%26a=2&b=3");
    }

    @Test
    void semicolon() {
        final PathAndQuery res = parse("/;?a=b;c=d");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/;");
        assertThat(res.query()).isEqualTo("a=b;c=d");

        // '%3B' in a query string should never be decoded into ';'.
        final PathAndQuery res2 = parse("/%3b?a=b%3Bc=d");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/;");
        assertThat(res2.query()).isEqualTo("a=b%3Bc=d");
    }

    @Test
    void equal() {
        final PathAndQuery res = parse("/=?a=b=1");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/=");
        assertThat(res.query()).isEqualTo("a=b=1");

        // '%3D' in a query string should never be decoded into '='.
        final PathAndQuery res2 = parse("/%3D?a%3db=1");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/=");
        assertThat(res2.query()).isEqualTo("a%3Db=1");
    }

    @Test
    void sharp() {
        final PathAndQuery res = parse("/#?a=b#1");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/#");
        assertThat(res.query()).isEqualTo("a=b#1");

        // '%23' in a query string should never be decoded into '#'.
        final PathAndQuery res2 = parse("/%23?a=b%231");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/#");
        assertThat(res2.query()).isEqualTo("a=b%231");
    }

    @Test
    void allReservedCharacters() {
        final PathAndQuery res = parse("/#/:[]@!$&'()*+,;=?a=/#/:[]@!$&'()*+,;=");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/#/:[]@!$&'()*+,;=");
        assertThat(res.query()).isEqualTo("a=/#/:[]@!$&'()*+,;=");

        final PathAndQuery res2 =
                parse("/%23%2F%3A%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F" +
                      "?a=%23%2F%3A%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/#%2F:[]@!$&'()*+,;=?");
        assertThat(res2.query()).isEqualTo("a=%23%2F%3A%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F");
    }

    @Test
    void doubleQuote() {
        final PathAndQuery res = parse("/\"?\"");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/%22");
        assertThat(res.query()).isEqualTo("%22");
    }

    private static void assertProhibited(String rawPath) {
        assertThat(parse(rawPath))
                .as("Ensure parse(\"%s\") returns null.", rawPath)
                .isNull();
    }

    private static void assertQueryStringAllowed(String rawPath) {
        assertQueryStringAllowed(rawPath, false);
    }

    private static void assertQueryStringAllowed(String rawPath, boolean allowDoubleDotsInQueryString) {
        assertThat(rawPath).startsWith("/?");
        final String expectedQuery = rawPath.substring(2);
        assertQueryStringAllowed(rawPath, expectedQuery, allowDoubleDotsInQueryString);
    }

    private static void assertQueryStringAllowed(String rawPath, String expectedQuery) {
        assertQueryStringAllowed(rawPath, expectedQuery, false);
    }

    private static void assertQueryStringAllowed(String rawPath, String expectedQuery,
                                                 boolean allowDoubleDotsInQueryString) {
        final PathAndQuery res = parse(rawPath, allowDoubleDotsInQueryString);
        assertThat(res)
                .as("parse(\"%s\") must return non-null.", rawPath)
                .isNotNull();
        assertThat(res.query())
                .as("parse(\"%s\").query() must return \"%s\".", rawPath, expectedQuery)
                .isEqualTo(expectedQuery);
    }


    @Nullable
    private static PathAndQuery parse(@Nullable String rawPath) {
        return parse(rawPath, false);
    }

    @Nullable
    private static PathAndQuery parse(@Nullable String rawPath, boolean allowDoubleDotsInQueryString) {
        final PathAndQuery res = PathAndQuery.parse(rawPath, allowDoubleDotsInQueryString);
        if (res != null) {
            logger.info("parse({}) => path: {}, query: {}", rawPath, res.path(), res.query());
        } else {
            logger.info("parse({}) => null", rawPath);
        }
        return res;
    }
}
