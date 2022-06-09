/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.conditional;

import static com.linecorp.armeria.server.conditional.ETag.validateEtag;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.AssertionsForClassTypes;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class ETagTest {

    @Test
    void asHeaderValue() {
        assertThat(new ETag("foobar", true).asHeaderValue()).isEqualTo("W/\"foobar\"");
        assertThat(new ETag("foobar", false).asHeaderValue()).isEqualTo("\"foobar\"");
    }

    @Test
    void testValidateEtag() {
        assertThatThrownBy(() -> new ETag(" ", true))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(validateEtag("!"))
                .isTrue();
        assertThat(validateEtag("Foo!Foo#»☺$+~"))
                .isTrue();
        assertThat(validateEtag("\""))
                .isFalse();
        assertThat(validateEtag("\u007f"))
                .isFalse();
        assertThat(validateEtag("\u0080"))
                .isTrue();
    }

    @Test
    void asteriskParsing() {
        AssertionsForClassTypes.assertThat(ETag.parseHeader(null))
                               .isNull();
        AssertionsForClassTypes.assertThat(ETag.parseHeader("*"))
                               .isSameAs(ETag.ASTERISK_ETAG);
        AssertionsForClassTypes.assertThat(ETag.parseHeader("\"*\""))
                               .isNotSameAs(ETag.ASTERISK_ETAG)
                               .isEqualTo(ImmutableList.of(new ETag("*", false)));
    }

    void quoting() {
        AssertionsForClassTypes.assertThat(ETag.parseHeader("\"missingEndingQuote"))
                               .isNull();
        AssertionsForClassTypes.assertThat(ETag.parseHeader("missingInitialQuote\""))
                               .isNull();
        AssertionsForClassTypes.assertThat(ETag.parseHeader("\"foo\""))
                               .isEqualTo(ImmutableList.of(new ETag("foo", false)));
        AssertionsForClassTypes.assertThat(ETag.parseHeader("W/\"foo\""))
                               .isEqualTo(ImmutableList.of(new ETag("foo", true)));
    }

    @Test
    void multiple() {
        AssertionsForClassTypes.assertThat(ETag.parseHeader("\"foo\", W/\"bar\""))
                               .isEqualTo(ImmutableList.of(new ETag("foo", false), new ETag("bar", true)));
        AssertionsForClassTypes.assertThat(ETag.parseHeader("\"foo\", W/\"bar\", \"barbar\""))
                               .isEqualTo(ImmutableList.of(new ETag("foo", false),
                                                           new ETag("bar", true),
                                                           new ETag("barbar", false)));
        AssertionsForClassTypes.assertThat(ETag.parseHeader("\"foo\", W/\"bar\", \"barbar\"junk"))
                               .isNull();
        AssertionsForClassTypes.assertThat(ETag.parseHeader("\"foo\", junkW/\"bar\", \"barbar\""))
                               .isNull();
    }
}
