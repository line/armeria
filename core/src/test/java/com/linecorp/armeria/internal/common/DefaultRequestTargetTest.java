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

import static com.google.common.base.Strings.emptyToNull;
import static com.linecorp.armeria.internal.common.DefaultRequestTarget.removeMatrixVariables;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URISyntaxException;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.annotation.Nullable;

class DefaultRequestTargetTest {

    private static final Logger logger = LoggerFactory.getLogger(DefaultRequestTargetTest.class);

    private static final Set<String> QUERY_SEPARATORS = ImmutableSet.of("&", ";");

    @Test
    @SuppressWarnings("DataFlowIssue")
    void shouldThrowNpeOnNull() {
        assertThatThrownBy(() -> RequestTarget.forServer(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> RequestTarget.forClient(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void serverShouldRejectEmptyPath() {
        assertRejected(forServer(""));
    }

    @Test
    void serverShouldRejectRelativePath() {
        assertRejected(forServer("foo"));
        assertRejected(forServer("?"));
        assertRejected(forServer("#"));
        assertRejected(forServer("%2f")); // percent-encoded slash
        assertRejected(forServer("%2F")); // percent-encoded slash
    }

    @Test
    void clientShouldAcceptRelativePath() {
        assertAccepted(forClient(""), "/");
        assertAccepted(forClient("foo"), "/foo");
        assertAccepted(forClient("?foo"), "/", "foo");
        assertAccepted(forClient("#foo"), "/", null, "foo");
        assertAccepted(forClient("%2f"), "/%2F"); // percent-encoded slash
        assertAccepted(forClient("%2F"), "/%2F"); // percent-encoded slash
    }

    @Test
    void clientShouldPrependPrefix() {
        assertAccepted(forClient("", "/"), "/");
        assertAccepted(forClient("foo", "/"), "/foo");
        assertAccepted(forClient("foo", "/bar"), "/bar/foo");
        assertAccepted(forClient("foo", "/bar/"), "/bar/foo");
        assertAccepted(forClient("/foo", "/bar"), "/bar/foo");
        assertAccepted(forClient("/foo", "/bar/"), "/bar/foo");
    }

    @Test
    void clientThrowsOnNonAbsolutePrefix() {
        assertThatThrownBy(() -> forClient("/", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> forClient("/", "relative-prefix"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> forClient("/", "relative-prefix/"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("badDoubleDotPatterns")
    void serverShouldRejectBadDoubleDotPatterns(String pattern) {
        assertRejected(forServer(toAbsolutePath(pattern))); // in a path
        assertRejected(forServer("/?" + pattern)); // in a free-form query
        assertRejected(forServer("/?" + pattern + "=foo")); // in a query name
        assertRejected(forServer("/?foo=" + pattern)); // in a query value

        QUERY_SEPARATORS.forEach(qs -> {
            // Query names and values that appear in the middle:
            assertRejected(forServer("/?a=b" + qs + pattern + "=c" + qs + "d=e"));
            assertRejected(forServer("/?a=b" + qs + "c=" + pattern + qs + "d=e"));
            // Query names and values appear lastly:
            assertRejected(forServer("/?a=b" + qs + pattern + "=c"));
            assertRejected(forServer("/?a=b" + qs + "c=" + pattern));
        });
    }

    @ParameterizedTest
    @MethodSource("goodDoubleDotPatterns")
    void serverShouldAcceptGoodDoubleDotPatterns(String pattern) {
        assertThat(forServer(toAbsolutePath(pattern))).isNotNull(); // in a path
        assertThat(forServer("/?" + pattern)).isNotNull(); // in a free-form query
        assertThat(forServer("/?" + pattern + "=foo")).isNotNull(); // in a query name
        assertThat(forServer("/?foo=" + pattern)).isNotNull(); // in a query value

        QUERY_SEPARATORS.forEach(qs -> {
            // Query names and values that appear in the middle:
            assertThat(forServer("/?a=b" + qs + pattern + "=c" + qs + "d=e")).isNotNull();
            assertThat(forServer("/?a=b" + qs + "c=" + pattern + qs + "d=e")).isNotNull();
            // Query names and values appear lastly:
            assertThat(forServer("/?a=b" + qs + pattern + "=c")).isNotNull();
            assertThat(forServer("/?a=b" + qs + "c=" + pattern)).isNotNull();
        });
    }

    /**
     * {@link RequestTarget} treats the first `=` in a query parameter as `/` internally to simplify
     * the detection the logic. This test makes sure the `=` appeared later is not treated as `/`.
     */
    @Test
    void dotsAndEqualsInNameValueQuery() {
        QUERY_SEPARATORS.forEach(qs -> {
            assertThat(forServer("/?a=..=" + qs + "b=..=")).satisfies(res -> {
                assertThat(res).isNotNull();
                assertThat(res.query()).isEqualTo("a=..=" + qs + "b=..=");
                assertThat(QueryParams.fromQueryString(res.query(), true)).containsExactly(
                        Maps.immutableEntry("a", "..="),
                        Maps.immutableEntry("b", "..=")
                );
            });

            assertThat(forServer("/?a==.." + qs + "b==..")).satisfies(res -> {
                assertThat(res).isNotNull();
                assertThat(res.query()).isEqualTo("a==.." + qs + "b==..");
                assertThat(QueryParams.fromQueryString(res.query(), true)).containsExactly(
                        Maps.immutableEntry("a", "=.."),
                        Maps.immutableEntry("b", "=..")
                );
            });

            assertThat(forServer("/?a==..=" + qs + "b==..=")).satisfies(res -> {
                assertThat(res).isNotNull();
                assertThat(res.query()).isEqualTo("a==..=" + qs + "b==..=");
                assertThat(QueryParams.fromQueryString(res.query(), true)).containsExactly(
                        Maps.immutableEntry("a", "=..="),
                        Maps.immutableEntry("b", "=..=")
                );
            });
        });
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldRejectInvalidPercentEncoding(Mode mode) {
        assertRejected(parse(mode, "/%"));
        assertRejected(parse(mode, "/%0"));
        assertRejected(parse(mode, "/%0X"));
        assertRejected(parse(mode, "/%X0"));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldRejectControlChars(Mode mode) {
        assertRejected(parse(mode, "/\0"));
        assertRejected(parse(mode, "/a\nb"));
        assertRejected(parse(mode, "/a\u007fb"));

        // Escaped
        assertRejected(parse(mode, "/%00"));
        assertRejected(parse(mode, "/a%09b"));
        assertRejected(parse(mode, "/a%0ab"));
        assertRejected(parse(mode, "/a%0db"));
        assertRejected(parse(mode, "/a%7fb"));

        // With query string
        assertRejected(parse(mode, "/\0?c"));
        assertRejected(parse(mode, "/a\tb?c"));
        assertRejected(parse(mode, "/a\nb?c"));
        assertRejected(parse(mode, "/a\rb?c"));
        assertRejected(parse(mode, "/a\u007fb?c"));

        // With query string with control chars
        assertRejected(parse(mode, "/?\0"));
        assertRejected(parse(mode, "/?%00"));
        assertRejected(parse(mode, "/?a\u007fb"));
        assertRejected(parse(mode, "/?a%7Fb"));

        // However, 0x0A, 0x0D, 0x09 should be accepted in a query string.
        assertAccepted(parse(mode, "/?a\tb"), "/", "a%09b");
        assertAccepted(parse(mode, "/?a\nb"), "/", "a%0Ab");
        assertAccepted(parse(mode, "/?a\rb"), "/", "a%0Db");
        assertAccepted(parse(mode, "/?a%09b"), "/", "a%09b");
        assertAccepted(parse(mode, "/?a%0Ab"), "/", "a%0Ab");
        assertAccepted(parse(mode, "/?a%0Db"), "/", "a%0Db");

        if (mode == Mode.CLIENT) {
            // All sort of control characters should be rejected in a fragment.
            assertRejected(forClient("/#\0"));
            assertRejected(forClient("/#%00"));
            assertRejected(forClient("/#a\u007fb"));
            assertRejected(forClient("/#a%7Fb"));
            assertRejected(forClient("/#a\tb"));
            assertRejected(forClient("/#a\nb"));
            assertRejected(forClient("/#a\rb"));
            assertRejected(forClient("/#a%09b"));
            assertRejected(forClient("/#a%0Ab"));
            assertRejected(forClient("/#a%0Db"));
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldAcceptPercentEncodedPercent(Mode mode) {
        assertAccepted(parse(mode, "/%25"), "/%25");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldNotDecodeSlash(Mode mode) {
        assertAccepted(parse(mode, "/%2F?%2F"), "/%2F", "%2F"); // path & query
        assertAccepted(parse(mode, "/foo%2F"), "/foo%2F"); // path only
        assertAccepted(parse(mode, "/?%2f=%2F"), "/", "%2F=%2F"); // query only
    }

    @Test
    void serverShouldCleanUpConsecutiveSlashes() {
        assertAccepted(
                forServer("/path//with///consecutive////slashes" +
                          "?/query//with///consecutive////slashes"),
                "/path/with/consecutive/slashes",
                "/query//with///consecutive////slashes");

        // Encoded slashes should be retained.
        assertAccepted(
                forServer("/path%2F/with/%2F/consecutive//%2F%2Fslashes" +
                          "?/query%2F/with/%2F/consecutive//%2F%2Fslashes"),
                "/path%2F/with/%2F/consecutive/%2F%2Fslashes",
                "/query%2F/with/%2F/consecutive//%2F%2Fslashes");
    }

    @Test
    void clientShouldNotCleanUpConsecutiveSlashes() {
        assertAccepted(
                forClient("/path//with///consecutive////slashes" +
                          "?/query//with///consecutive////slashes" +
                          "#/fragment//with///consecutive////slashes"),
                "/path//with///consecutive////slashes",
                "/query//with///consecutive////slashes",
                "/fragment//with///consecutive////slashes");

        // Encoded slashes should be retained.
        assertAccepted(
                forClient("/path%2F/with/%2F/consecutive//%2F%2Fslashes" +
                          "?/query%2F/with/%2F/consecutive//%2F%2Fslashes" +
                          "#/fragment%2F/with/%2F/consecutive//%2F%2Fslashes"),
                "/path%2F/with/%2F/consecutive//%2F%2Fslashes",
                "/query%2F/with/%2F/consecutive//%2F%2Fslashes",
                "/fragment%2F/with/%2F/consecutive//%2F%2Fslashes");
    }

    @Test
    void clientShouldRetainConsecutiveSlashesInFragment() {
        assertAccepted(forClient("/#/////"), "/", null, "/////");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldAcceptColon(Mode mode) {
        assertThat(parse(mode, "/:")).isNotNull();
        assertThat(parse(mode, "/:/")).isNotNull();
        assertThat(parse(mode, "/a/:")).isNotNull();
        assertThat(parse(mode, "/a/:/")).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    @SuppressWarnings("checkstyle:AvoidEscapedUnicodeCharacters")
    void shouldNormalizeUnicode(Mode mode) {
        // 2- and 3-byte UTF-8
        assertAccepted(parse(mode, "/\u00A2?\u20AC"), "/%C2%A2", "%E2%82%AC");

        // 4-byte UTF-8
        assertAccepted(parse(mode, "/\uD800\uDF48"), "/%F0%90%8D%88");

        // 5- and 6-byte forms are only theoretically possible, so we won't test them here.
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldNormalizeEncodedUnicode(Mode mode) {
        final String encodedPath = "/%ec%95%88";
        final String encodedQuery = "%eb%85%95";
        assertAccepted(parse(mode, encodedPath + '?' + encodedQuery),
                       Ascii.toUpperCase(encodedPath),
                       Ascii.toUpperCase(encodedQuery));
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldNotEncodeWhenUnnecessary(Mode mode) {
        assertAccepted(parse(mode, "/a?b=c"), "/a", "b=c");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldNormalizeSpace(Mode mode) {
        assertAccepted(parse(mode, "/ ? "), "/%20", "+");
        assertAccepted(parse(mode, "/%20?%20"), "/%20", "+");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldNormalizePlusSign(Mode mode) {
        assertAccepted(parse(mode, "/+?a+b=c+d"), "/+", "a+b=c+d");
        assertAccepted(parse(mode, "/%2b?a%2bb=c%2bd"), "/+", "a%2Bb=c%2Bd");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldNormalizeAmpersand(Mode mode) {
        assertAccepted(parse(mode, "/&?a=1&a=2&b=3"), "/&", "a=1&a=2&b=3");
        assertAccepted(parse(mode, "/%26?a=1%26a=2&b=3"), "/&", "a=1%26a=2&b=3");
    }

    @Test
    void serverShouldRemoveMatrixVariablesWhenNotAllowed() {
        // Not allowed
        assertAccepted(forServer("/foo;a=b?c=d;e=f"), "/foo", "c=d;e=f");
        // Allowed.
        assertAccepted(forServer("/;a=b?c=d;e=f", true), "/;a=b", "c=d;e=f");
        // '%3B' should never be decoded into ';'.
        assertAccepted(forServer("/%3B?a=b%3Bc=d"), "/%3B", "a=b%3Bc=d");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldNormalizeEqualSign(Mode mode) {
        assertAccepted(parse(mode, "/=?a=b=1"), "/=", "a=b=1");
        // '%3D' in a query string should never be decoded into '='.
        assertAccepted(parse(mode, "/%3D?a%3db=1"), "/=", "a%3Db=1");
    }

    @Test
    void shouldReserveQuestionMark() throws URISyntaxException {
        // '%3F' must not be decoded into '?'.
        assertAccepted(forServer("/abc%3F.json?a=%3F"), "/abc%3F.json", "a=%3F");
        assertAccepted(forClient("/abc%3F.json?a=%3F"), "/abc%3F.json", "a=%3F");
    }

    @Test
    void reserveSemicolonWhenAllowed() {
        // '%3B' is decoded into ';' when allowSemicolonInPathComponent is true.
        assertAccepted(forServer("/abc%3B?a=%3B", true), "/abc;", "a=%3B");
        assertAccepted(forServer("/abc%3B?a=%3B"), "/abc%3B", "a=%3B");

        assertAccepted(forServer("/abc%3B", true), "/abc;");
        assertAccepted(forServer("/abc%3B"), "/abc%3B");

        // Client always decodes '%3B' into ';'.
        assertAccepted(forClient("/abc%3B?a=%3B"), "/abc;", "a=%3B");
    }

    @Test
    void serverShouldNormalizePoundSign() {
        // '#' must be encoded into '%23'.
        assertAccepted(forServer("/#?a=b#1"), "/%23", "a=b%231");

        // '%23' should never be decoded into '#'.
        assertAccepted(forServer("/%23?a=b%231"), "/%23", "a=b%231");
    }

    @Test
    void clientShouldTreatPoundSignAsFragment() {
        // '#' must be treated as a fragment marker.
        assertAccepted(forClient("/?a=b#1"), "/", "a=b", "1");
        assertAccepted(forClient("/#?a=b#1"), "/", null, "?a=b%231");

        // '%23' should never be treated as a fragment marker.
        assertAccepted(forClient("/%23?a=b%231"), "/%23", "a=b%231");
    }

    @Test
    void serverShouldHandleReservedCharacters() {
        assertAccepted(forServer("/#/:@!$&'()*+,=?a=/#/:[]@!$&'()*+,="),
                       "/%23/:@!$&'()*+,=",
                       "a=/%23/:[]@!$&'()*+,=");
        assertAccepted(forServer("/%23%2F%3A%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F" +
                                 "?a=%23%2F%3A%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F"),
                       "/%23%2F:@!$&'()*+,%3B=%3F",
                       "a=%23%2F%3A%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F");
    }

    @Test
    void clientShouldHandleReservedCharacters() {
        assertAccepted(forClient("/:@!$&'()*+,;=?a=/:[]@!$&'()*+,;=#/:@!$&'()*+,;="),
                       "/:@!$&'()*+,;=",
                       "a=/:[]@!$&'()*+,;=",
                       "/:@!$&'()*+,;=");
        assertAccepted(forClient("/%23%2F%3A%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F" +
                                 "?a=%23%2F%3A%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F" +
                                 "#%23%2F%3A%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F"),
                       "/%23%2F:@!$&'()*+,;=%3F",
                       "a=%23%2F%3A%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F",
                       "%23%2F:@!$&'()*+,;=%3F");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldHandleDoubleQuote(Mode mode) {
        assertAccepted(parse(mode, "/\"?\""), "/%22", "%22");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldHandleSquareBracketsInPath(Mode mode) {
        assertAccepted(parse(mode, "/@/:[]!$&'()*+,="), "/@/:%5B%5D!$&'()*+,=");
        assertAccepted(parse(mode, "/%40%2F%3A%5B%5D%21%24%26%27%28%29%2A%2B%2C%3D%3F"),
                       "/@%2F:%5B%5D!$&'()*+,=%3F");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldAcceptAsteriskPath(Mode mode) {
        assertAccepted(parse(mode, "*"), "*");
    }

    @ParameterizedTest
    @CsvSource({
            "a://b/,         a, b, /,,",
            "a://b/c,        a, b, /c,,",
            "a://b/c?d,      a, b, /c, d,",
            "a://b/c#d,      a, b, /c,, d",
            "a://b/c?d#e,    a, b, /c, d, e",
            "a://b/c#d?e,    a, b, /c,, d?e",
            // Empty path
            "a://b,          a, b, /,,",
            "a://b?c,        a, b, /, c,",
            "a://b#c,        a, b, /,, c",
            "a://b?c#d,      a, b, /, c, d",
            "a://b#c?d,      a, b, /,, c?d",
            // Userinfo and port in authority
            "a://b@c:80,     a, c:80, /,,",
            // IP addresses
            "a://127.0.0.1/, a, 127.0.0.1, /,,",
            "a://[::1]:80/,  a, [::1]:80, /,,",
            // default port numbers should be omitted
            "http://a:80/,   http, a, /,,",
            "http://a:443/,  http, a:443, /,,",
            "https://a:80/,  https, a:80, /,,",
            "https://a:443/, https, a, /,,",
    })
    void clientShouldAcceptAbsoluteUri(String uri,
                                       String expectedScheme, String expectedAuthority, String expectedPath,
                                       @Nullable String expectedQuery, @Nullable String expectedFragment) {

        final RequestTarget res = forClient(uri);
        assertThat(res.scheme()).isEqualTo(expectedScheme);
        assertThat(res.authority()).isEqualTo(expectedAuthority);
        assertAccepted(res, expectedPath, emptyToNull(expectedQuery), emptyToNull(expectedFragment));
    }

    @Test
    void serverShouldRejectAbsoluteUri() {
        assertRejected(forServer("http://foo/bar"));
    }

    @Test
    void clientShouldRejectInvalidSchemeOrAuthority() {
        assertRejected(forClient("ht%tp://acme")); // bad scheme
        assertRejected(forClient("http://[acme")); // bad authority
        assertRejected(forClient("http:///"));     // empty authority
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void shouldYieldEmptyStringForEmptyQueryAndFragment(Mode mode) {
        assertAccepted(parse(mode, "/?"), "/", "");
        if (mode == Mode.CLIENT) {
            assertAccepted(forClient("/#"), "/", null, "");
            assertAccepted(forClient("/?#"), "/", "", "");
        }
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void testToString(Mode mode) {
        assertThat(parse(mode, "/")).asString().isEqualTo("/");
        assertThat(parse(mode, "/?")).asString().isEqualTo("/?");
        assertThat(parse(mode, "/?a=b")).asString().isEqualTo("/?a=b");

        if (mode == Mode.CLIENT) {
            assertThat(forClient("/#")).asString().isEqualTo("/#");
            assertThat(forClient("/?#")).asString().isEqualTo("/?#");
            assertThat(forClient("/?a=b#c=d")).asString().isEqualTo("/?a=b#c=d");
            assertThat(forClient("http://foo/bar?a=b#c=d")).asString().isEqualTo("http://foo/bar?a=b#c=d");
        }
    }

    @Test
    void testRemoveMatrixVariables() {
        assertThat(removeMatrixVariables("/foo")).isEqualTo("/foo");
        assertThat(removeMatrixVariables("/foo;")).isEqualTo("/foo");
        assertThat(removeMatrixVariables("/foo/")).isEqualTo("/foo/");
        assertThat(removeMatrixVariables("/foo/bar")).isEqualTo("/foo/bar");
        assertThat(removeMatrixVariables("/foo/bar;")).isEqualTo("/foo/bar");
        assertThat(removeMatrixVariables("/foo/bar/")).isEqualTo("/foo/bar/");
        assertThat(removeMatrixVariables("/foo;/bar")).isEqualTo("/foo/bar");
        assertThat(removeMatrixVariables("/foo;/bar;")).isEqualTo("/foo/bar");
        assertThat(removeMatrixVariables("/foo;/bar/")).isEqualTo("/foo/bar/");
        assertThat(removeMatrixVariables("/foo;a=b/bar")).isEqualTo("/foo/bar");
        assertThat(removeMatrixVariables("/foo;a=b/bar;")).isEqualTo("/foo/bar");
        assertThat(removeMatrixVariables("/foo;a=b/bar/")).isEqualTo("/foo/bar/");
        assertThat(removeMatrixVariables("/foo;a=b/bar/baz")).isEqualTo("/foo/bar/baz");
        assertThat(removeMatrixVariables("/foo;a=b/bar/baz;")).isEqualTo("/foo/bar/baz");
        assertThat(removeMatrixVariables("/foo;a=b/bar/baz/")).isEqualTo("/foo/bar/baz/");
        assertThat(removeMatrixVariables("/foo;a=b/bar;/baz")).isEqualTo("/foo/bar/baz");
        assertThat(removeMatrixVariables("/foo;a=b/bar;/baz;")).isEqualTo("/foo/bar/baz");
        assertThat(removeMatrixVariables("/foo;a=b/bar;/baz/")).isEqualTo("/foo/bar/baz/");
        assertThat(removeMatrixVariables("/foo;a=b/bar;c=d/baz")).isEqualTo("/foo/bar/baz");
        assertThat(removeMatrixVariables("/foo;a=b/bar;c=d/baz;")).isEqualTo("/foo/bar/baz");
        assertThat(removeMatrixVariables("/foo;a=b/bar;c=d/baz/")).isEqualTo("/foo/bar/baz/");

        // Invalid
        assertThat(removeMatrixVariables("/;a=b")).isNull();
        assertThat(removeMatrixVariables("/prefix/;a=b")).isNull();
    }

    private static void assertAccepted(@Nullable RequestTarget res, String expectedPath) {
        assertAccepted(res, expectedPath, null, null);
    }

    private static void assertAccepted(@Nullable RequestTarget res,
                                       String expectedPath,
                                       @Nullable String expectedQuery) {
        assertAccepted(res, expectedPath, expectedQuery, null);
    }

    private static void assertAccepted(@Nullable RequestTarget res,
                                       String expectedPath,
                                       @Nullable String expectedQuery,
                                       @Nullable String expectedFragment) {
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo(expectedPath);
        assertThat(res.query()).isEqualTo(expectedQuery);
        assertThat(res.fragment()).isEqualTo(expectedFragment);
    }

    private static void assertRejected(@Nullable RequestTarget res) {
        assertThat(res).isNull();
    }

    @Nullable
    private static RequestTarget parse(Mode mode, String rawPath) {
        switch (mode) {
            case SERVER:
                return forServer(rawPath);
            case CLIENT:
                return forClient(rawPath);
            default:
                throw new Error();
        }
    }

    @Nullable
    private static RequestTarget forServer(String rawPath) {
        return forServer(rawPath, false);
    }

    @Nullable
    private static RequestTarget forServer(String rawPath, boolean allowSemicolonInPathComponent) {
        final RequestTarget res = DefaultRequestTarget.forServer(rawPath, allowSemicolonInPathComponent, false);
        if (res != null) {
            logger.info("forServer({}) => path: {}, query: {}", rawPath, res.path(), res.query());
        } else {
            logger.info("forServer({}) => null", rawPath);
        }
        return res;
    }

    @Nullable
    private static RequestTarget forClient(String rawPath) {
        return forClient(rawPath, null);
    }

    @Nullable
    private static RequestTarget forClient(String rawPath, @Nullable String prefix) {
        final RequestTarget res = DefaultRequestTarget.forClient(rawPath, prefix);
        if (res != null) {
            logger.info("forClient({}, {}) => path: {}, query: {}, fragment: {}", rawPath, prefix, res.path(),
                        res.query(), res.fragment());
        } else {
            logger.info("forClient({}, {}) => null", rawPath, prefix);
        }
        return res;
    }

    private static String toAbsolutePath(String pattern) {
        return pattern.startsWith("/") ? pattern : '/' + pattern;
    }

    private enum Mode {
        SERVER,
        CLIENT
    }

    private static Stream<String> badDoubleDotPatterns() {
        return Stream.of(
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
                ".%2E%2F",

                // With matrix variables
                "..;a=b", "/..;a=b",
                "..;a=b/foo", "/..;a=b/foo",
                "foo/..;a=b", "/foo/..;a=b",
                "foo/..;a=b/", "/foo/..;a=b/",
                "foo/..;a=b/bar", "/foo/..;a=b/bar",
                ".%2e;a=b", "/.%2e;a=b", "%2E.;a=b/", "/%2E.;a=b/", ".%2E;a=b/", "/.%2E;a=b/",
                "foo/.%2e;a=b", "/foo/.%2e;a=b",
                "foo/%2E.;a=b/", "/foo/%2E.;a=b/",
                "foo/%2E.;a=b/bar", "/foo/%2E.;a=b/bar",
                "%2f..;a=b", "..;a=b%2F", "/..;a=b%2F", "%2F..;a=b/", "%2f..;a=b%2f",
                "/foo%2f..;a=b", "/foo%2f..;a=b/", "/foo/..;a=b%2f", "/foo%2F..;a=b%2F"
        );
    }

    private static Stream<String> goodDoubleDotPatterns() {
        return Stream.of(
                "..a", "a..", "a..b",
                "/..a", "/a..", "/a..b",
                "..a/", "a../", "a..b/",
                "/..a/", "/a../", "/a..b/"
        );
    }
}
