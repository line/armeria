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
package com.linecorp.armeria.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.google.common.base.Ascii;

public class PathAndQueryTest {
    @Test
    public void empty() {
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
    public void relative() {
        assertThat(PathAndQuery.parse("foo")).isNull();
    }

    @Test
    public void doubleDots() {
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
    public void hexadecimal() {
        assertThat(PathAndQuery.parse("/%")).isNull();
        assertThat(PathAndQuery.parse("/%0")).isNull();
        assertThat(PathAndQuery.parse("/%0X")).isNull();
        assertThat(PathAndQuery.parse("/%X0")).isNull();
    }

    @Test
    public void controlChars() {
        assertThat(PathAndQuery.parse("/\0")).isNull();
        assertThat(PathAndQuery.parse("/a\nb")).isNull();
        assertThat(PathAndQuery.parse("/a\u007fb")).isNull();

        // Escaped
        assertThat(PathAndQuery.parse("/%00")).isNull();
        assertThat(PathAndQuery.parse("/a%0db")).isNull();
        assertThat(PathAndQuery.parse("/a%7fb")).isNull();

        // With query string
        assertThat(PathAndQuery.parse("/\0?c")).isNull();
        assertThat(PathAndQuery.parse("/a\nb?c")).isNull();
        assertThat(PathAndQuery.parse("/a\u007fb?c")).isNull();

        // With query string with control chars
        assertThat(PathAndQuery.parse("/?\0")).isNull();
        assertThat(PathAndQuery.parse("/?a\nb")).isNull();
        assertThat(PathAndQuery.parse("/?a\u007fb")).isNull();
    }

    @Test
    public void percent() {
        final PathAndQuery res = PathAndQuery.parse("/%25");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/%25");
        assertThat(res.query()).isNull();
    }

    @Test
    public void slash() {
        final PathAndQuery res = PathAndQuery.parse("%2F?%2F");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/");
        assertThat(res.query()).isEqualTo("/");
    }

    @Test
    public void consecutiveSlashes() {
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
        assertThat(res2.query()).isEqualTo("/query//with///consecutive////slashes");
    }

    @Test
    public void colon() {
        assertThat(PathAndQuery.parse("/:")).isNull();
        assertThat(PathAndQuery.parse("/:/")).isNull();
        assertThat(PathAndQuery.parse("/a/:")).isNotNull();
        assertThat(PathAndQuery.parse("/a/:/")).isNotNull();
    }

    @Test
    public void rawUnicode() {
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
    public void encodedUnicode() {
        final String encodedPath = "/%ec%95%88";
        final String encodedQuery = "%eb%85%95";
        final PathAndQuery res = PathAndQuery.parse(encodedPath + '?' + encodedQuery);
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo(Ascii.toUpperCase(encodedPath));
        assertThat(res.query()).isEqualTo(Ascii.toUpperCase(encodedQuery));
    }

    @Test
    public void noEncoding() {
        final PathAndQuery res = PathAndQuery.parse("/a?b=c");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/a");
        assertThat(res.query()).isEqualTo("b=c");
    }

    @Test
    public void space() {
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
    public void plus() {
        final PathAndQuery res = PathAndQuery.parse("/+?+");
        assertThat(res).isNotNull();
        assertThat(res.path()).isEqualTo("/+");
        assertThat(res.query()).isEqualTo("+");

        final PathAndQuery res2 = PathAndQuery.parse("/%2b?%2b");
        assertThat(res2).isNotNull();
        assertThat(res2.path()).isEqualTo("/+");
        assertThat(res2.query()).isEqualTo("%2B");
    }
}
