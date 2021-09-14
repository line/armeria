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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.base.Ascii;

class PathAndQueryTest {
    @Test
    void empty() {
        final PathAndQuery res = PathAndQuery.parse(null);
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/");
        assertThat(res.query()).isNull();

        final PathAndQuery res2 = PathAndQuery.parse("");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/");
        assertThat(res2.query()).isNull();

        final PathAndQuery res3 = PathAndQuery.parse("?");
        assertThat(res3).isNotNull();
        assertThat(res3.path()).isEqualTo("/");
        assertThat(res3.query()).isEqualTo("");
    }

    @Test
    void relative() {
        assertThat(PathAndQuery.parse("foo")).isNull();
    }

    @Test
    void doubleDots() {
        assertThat(PathAndQuery.parse("/..")).isNull();
        assertThat(PathAndQuery.parse("/../")).isNull();
        assertThat(PathAndQuery.parse("/../foo")).isNull();
        assertThat(PathAndQuery.parse("/foo/..")).isNull();
        assertThat(PathAndQuery.parse("/foo/../")).isNull();
        assertThat(PathAndQuery.parse("/foo/../bar")).isNull();

        // Escaped
        assertThat(PathAndQuery.parse("/.%2e")).isNull();
        assertThat(PathAndQuery.parse("/%2E./")).isNull();
        assertThat(PathAndQuery.parse("/foo/.%2e")).isNull();
        assertThat(PathAndQuery.parse("/foo/%2E./")).isNull();

        // Not the double dots we are looking for.
        final PathAndQuery res = PathAndQuery.parse("/..a");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/..a");
        final PathAndQuery res2 = PathAndQuery.parse("/a..");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/a..");
    }

    @Test
    void hexadecimal() {
        assertThat(PathAndQuery.parse("/%")).isNull();
        assertThat(PathAndQuery.parse("/%0")).isNull();
        assertThat(PathAndQuery.parse("/%0X")).isNull();
        assertThat(PathAndQuery.parse("/%X0")).isNull();
    }

    @Test
    void controlChars() {
        assertThat(PathAndQuery.parse("/\0")).isNull();
        assertThat(PathAndQuery.parse("/a\nb")).isNull();
        assertThat(PathAndQuery.parse("/a\u007fb")).isNull();

        // Escaped
        assertThat(PathAndQuery.parse("/%00")).isNull();
        assertThat(PathAndQuery.parse("/a%09b")).isNull();
        assertThat(PathAndQuery.parse("/a%0ab")).isNull();
        assertThat(PathAndQuery.parse("/a%0db")).isNull();
        assertThat(PathAndQuery.parse("/a%7fb")).isNull();

        // With query string
        assertThat(PathAndQuery.parse("/\0?c")).isNull();
        assertThat(PathAndQuery.parse("/a\tb?c")).isNull();
        assertThat(PathAndQuery.parse("/a\nb?c")).isNull();
        assertThat(PathAndQuery.parse("/a\rb?c")).isNull();
        assertThat(PathAndQuery.parse("/a\u007fb?c")).isNull();

        // With query string with control chars
        assertThat(PathAndQuery.parse("/?\0")).isNull();
        assertThat(PathAndQuery.parse("/?%00")).isNull();
        assertThat(PathAndQuery.parse("/?a\u007fb")).isNull();
        assertThat(PathAndQuery.parse("/?a%7Fb")).isNull();
        // However, 0x0A, 0x0D, 0x09 should be accepted in a query string.
        assertThat(PathAndQuery.parse("/?a\tb").query()).isEqualTo("a%09b");
        assertThat(PathAndQuery.parse("/?a\nb").query()).isEqualTo("a%0Ab");
        assertThat(PathAndQuery.parse("/?a\rb").query()).isEqualTo("a%0Db");
        assertThat(PathAndQuery.parse("/?a%09b").query()).isEqualTo("a%09b");
        assertThat(PathAndQuery.parse("/?a%0Ab").query()).isEqualTo("a%0Ab");
        assertThat(PathAndQuery.parse("/?a%0Db").query()).isEqualTo("a%0Db");
    }

    @Test
    void percent() {
        final PathAndQuery res = PathAndQuery.parse("/%25");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/%25");
        assertThat(res.query()).isNull();
    }

    @Test
    void slash() {
        final PathAndQuery res = PathAndQuery.parse("%2F?%2F");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/");
        assertThat(res.query()).isEqualTo("%2F");
    }

    @Test
    void consecutiveSlashes() {
        final PathAndQuery res = PathAndQuery.parse(
                "/path//with///consecutive////slashes?/query//with///consecutive////slashes");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/path/with/consecutive/slashes");
        assertThat(res.query()).isEqualTo("/query//with///consecutive////slashes");

        // Encoded slashes
        final PathAndQuery res2 = PathAndQuery.parse(
                "/path%2F/with/%2F/consecutive//%2F%2Fslashes?/query%2F/with/%2F/consecutive//%2F%2Fslashes");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/path/with/consecutive/slashes");
        assertThat(res2.query()).isEqualTo("/query%2F/with/%2F/consecutive//%2F%2Fslashes");
    }

    @Test
    void colon() {
        assertThat(PathAndQuery.parse("/:")).isNotNull();
        assertThat(PathAndQuery.parse("/:/")).isNotNull();
        assertThat(PathAndQuery.parse("/a/:")).isNotNull();
        assertThat(PathAndQuery.parse("/a/:/")).isNotNull();
    }

    @Test
    void rawUnicode() {
        // 2- and 3-byte UTF-8
        final PathAndQuery res1 = PathAndQuery.parse("/\u00A2?\u20AC"); // ¢ and €
        assertThat(res1).isNotNull();
        assertThat(res1.path()).isEqualTo("/%C2%A2");
        assertThat(res1.query()).isEqualTo("%E2%82%AC");

        // 4-byte UTF-8
        final PathAndQuery res2 = PathAndQuery.parse("/\uD800\uDF48"); // 𐍈
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/%F0%90%8D%88");
        assertThat(res2.query()).isNull();

        // 5- and 6-byte forms are only theoretically possible, so we won't test them here.
    }

    @Test
    void encodedUnicode() {
        final String encodedPath = "/%ec%95%88";
        final String encodedQuery = "%eb%85%95";
        final PathAndQuery res = PathAndQuery.parse(encodedPath + '?' + encodedQuery);
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo(Ascii.toUpperCase(encodedPath));
        assertThat(res.query()).isEqualTo(Ascii.toUpperCase(encodedQuery));
    }

    @Test
    void noEncoding() {
        final PathAndQuery res = PathAndQuery.parse("/a?b=c");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/a");
        assertThat(res.query()).isEqualTo("b=c");
    }

    @Test
    void space() {
        final PathAndQuery res = PathAndQuery.parse("/ ? ");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/%20");
        assertThat(res.query()).isEqualTo("+");

        final PathAndQuery res2 = PathAndQuery.parse("/%20?%20");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/%20");
        assertThat(res2.query()).isEqualTo("+");
    }

    @Test
    void plus() {
        final PathAndQuery res = PathAndQuery.parse("/+?a+b=c+d");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/+");
        assertThat(res.query()).isEqualTo("a+b=c+d");

        final PathAndQuery res2 = PathAndQuery.parse("/%2b?a%2bb=c%2bd");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/+");
        assertThat(res2.query()).isEqualTo("a%2Bb=c%2Bd");
    }

    @Test
    void ampersand() {
        final PathAndQuery res = PathAndQuery.parse("/&?a=1&a=2&b=3");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/&");
        assertThat(res.query()).isEqualTo("a=1&a=2&b=3");

        // '%26' in a query string should never be decoded into '&'.
        final PathAndQuery res2 = PathAndQuery.parse("/%26?a=1%26a=2&b=3");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/&");
        assertThat(res2.query()).isEqualTo("a=1%26a=2&b=3");
    }

    @Test
    void semicolon() {
        final PathAndQuery res = PathAndQuery.parse("/;?a=b;c=d");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/;");
        assertThat(res.query()).isEqualTo("a=b;c=d");

        // '%3B' in a query string should never be decoded into ';'.
        final PathAndQuery res2 = PathAndQuery.parse("/%3b?a=b%3Bc=d");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/;");
        assertThat(res2.query()).isEqualTo("a=b%3Bc=d");
    }

    @Test
    void equal() {
        final PathAndQuery res = PathAndQuery.parse("/=?a=b=1");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/=");
        assertThat(res.query()).isEqualTo("a=b=1");

        // '%3D' in a query string should never be decoded into '='.
        final PathAndQuery res2 = PathAndQuery.parse("/%3D?a%3db=1");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/=");
        assertThat(res2.query()).isEqualTo("a%3Db=1");
    }

    @Test
    void sharp() {
        final PathAndQuery res = PathAndQuery.parse("/#?a=b#1");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/#");
        assertThat(res.query()).isEqualTo("a=b#1");

        // '%23' in a query string should never be decoded into '#'.
        final PathAndQuery res2 = PathAndQuery.parse("/%23?a=b%231");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/#");
        assertThat(res2.query()).isEqualTo("a=b%231");
    }

    @Test
    void allReservedCharacters() {
        final PathAndQuery res = PathAndQuery.parse("/#/:[]@!$&'()*+,;=?a=/#/:[]@!$&'()*+,;=");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/#/:[]@!$&'()*+,;=");
        assertThat(res.query()).isEqualTo("a=/#/:[]@!$&'()*+,;=");

        final PathAndQuery res2 =
                PathAndQuery.parse("/%23%2F%3A%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F" +
                                   "?a=%23%2F%3A%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/#/:[]@!$&'()*+,;=?");
        assertThat(res2.query()).isEqualTo("a=%23%2F%3A%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%3F");
    }

    @Test
    void doubleQuote() {
        final PathAndQuery res = PathAndQuery.parse("/\"?\"");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/%22");
        assertThat(res.query()).isEqualTo("%22");
    }
}
