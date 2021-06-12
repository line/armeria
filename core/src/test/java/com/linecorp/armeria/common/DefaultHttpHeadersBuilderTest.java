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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

class DefaultHttpHeadersBuilderTest {

    @Test
    void add() {
        final HttpHeaders headers = HttpHeaders.builder()
                                               .add("a", "b")
                                               .add("c", ImmutableList.of("d", "e"))
                                               .add("f", "g", "h")
                                               .add(ImmutableMap.of("i", "j"))
                                               .build();
        assertThat(headers).containsExactly(
                Maps.immutableEntry(HttpHeaderNames.of("a"), "b"),
                Maps.immutableEntry(HttpHeaderNames.of("c"), "d"),
                Maps.immutableEntry(HttpHeaderNames.of("c"), "e"),
                Maps.immutableEntry(HttpHeaderNames.of("f"), "g"),
                Maps.immutableEntry(HttpHeaderNames.of("f"), "h"),
                Maps.immutableEntry(HttpHeaderNames.of("i"), "j"));
    }

    @Test
    void set() {
        final HttpHeaders headers = HttpHeaders.builder()
                                               .add("a", "b")
                                               .add("c", ImmutableList.of("d", "e"))
                                               .add("f", "g", "h")
                                               .add(ImmutableMap.of("i", "j"))
                                               .set("a", "B")
                                               .set("c", ImmutableList.of("D", "E"))
                                               .set("f", "G", "H")
                                               .set(ImmutableMap.of("i", "J"))
                                               .build();
        assertThat(headers).containsExactly(
                Maps.immutableEntry(HttpHeaderNames.of("a"), "B"),
                Maps.immutableEntry(HttpHeaderNames.of("c"), "D"),
                Maps.immutableEntry(HttpHeaderNames.of("c"), "E"),
                Maps.immutableEntry(HttpHeaderNames.of("f"), "G"),
                Maps.immutableEntry(HttpHeaderNames.of("f"), "H"),
                Maps.immutableEntry(HttpHeaderNames.of("i"), "J"));
    }

    @Test
    void mutation() {
        final HttpHeaders headers = HttpHeaders.of("a", "b");
        final HttpHeaders headers2 = headers.toBuilder().set("a", "c").build();
        assertThat(headers).isNotSameAs(headers2);
        assertThat(headers).isNotEqualTo(headers2);
        assertThat(headers).containsExactly(Maps.immutableEntry(HttpHeaderNames.of("a"), "b"));
        assertThat(headers2).containsExactly(Maps.immutableEntry(HttpHeaderNames.of("a"), "c"));
    }

    @Test
    void mutationEndOfStreamOnly() {
        final HttpHeaders headers = HttpHeaders.of("a", "b");
        final HttpHeaders headers2 = headers.toBuilder().endOfStream(true).build();
        assertThat(headers.isEndOfStream()).isFalse();
        assertThat(headers2.isEndOfStream()).isTrue();
        assertThat(headers).isNotSameAs(headers2);
        assertThat(headers).isNotEqualTo(headers2);
        assertThat(headers).containsExactly(Maps.immutableEntry(HttpHeaderNames.of("a"), "b"));
        assertThat(headers2).containsExactly(Maps.immutableEntry(HttpHeaderNames.of("a"), "b"));
    }

    @Test
    void mutationAfterBuild() {
        final HttpHeaders headers = HttpHeaders.of("a", "b");
        final DefaultHttpHeadersBuilder builder = (DefaultHttpHeadersBuilder) headers.toBuilder();

        // Initial state
        assertThat(builder.parent()).isSameAs(headers);
        assertThat(builder.delegate()).isNull();

        // 1st mutation
        builder.add("c", "d");
        assertThat(builder.parent()).isSameAs(headers);
        assertThat(builder.delegate()).isNotNull().isNotSameAs(builder.parent());

        // 1st promotion
        HttpHeadersBase oldDelegate = builder.delegate();
        final HttpHeaders headers2 = builder.build();
        assertThat(headers2).isNotSameAs(headers);
        assertThat(((HttpHeadersBase) headers2).entries).isNotSameAs(((HttpHeadersBase) headers).entries);
        assertThat(builder.parent()).isSameAs(oldDelegate);
        assertThat(builder.delegate()).isNull();

        // 2nd mutation
        builder.add("e", "f");
        assertThat(builder.parent()).isSameAs(oldDelegate);
        assertThat(builder.delegate()).isNotNull().isNotSameAs(builder.parent());

        // 2nd promotion
        oldDelegate = builder.delegate();
        final HttpHeaders headers3 = builder.build();
        assertThat(headers3).isNotSameAs(headers);
        assertThat(headers3).isNotSameAs(headers2);
        assertThat(((HttpHeadersBase) headers3).entries).isNotSameAs(((HttpHeadersBase) headers).entries);
        assertThat(((HttpHeadersBase) headers3).entries).isNotSameAs(((HttpHeadersBase) headers2).entries);
        assertThat(builder.parent()).isSameAs(oldDelegate);
        assertThat(builder.delegate()).isNull();

        // 3rd mutation, to make sure it doesn't affect the previously built headers.
        builder.clear();

        // Check the content.
        assertThat(headers).isNotSameAs(headers3);
        assertThat(headers).containsExactly(Maps.immutableEntry(HttpHeaderNames.of("a"), "b"));
        assertThat(headers2).containsExactly(Maps.immutableEntry(HttpHeaderNames.of("a"), "b"),
                                             Maps.immutableEntry(HttpHeaderNames.of("c"), "d"));
        assertThat(headers3).containsExactly(Maps.immutableEntry(HttpHeaderNames.of("a"), "b"),
                                             Maps.immutableEntry(HttpHeaderNames.of("c"), "d"),
                                             Maps.immutableEntry(HttpHeaderNames.of("e"), "f"));
    }

    @Test
    void noMutationNoCopy() {
        final HttpHeaders headers = HttpHeaders.of("a", "b");
        assertThat(headers.toBuilder().build()).isSameAs(headers);

        // Failed removal should not make a copy.
        final DefaultHttpHeadersBuilder builder = (DefaultHttpHeadersBuilder) headers.toBuilder();
        builder.getAndRemove("c");
        builder.getAndRemove("c", "d");
        builder.getIntAndRemove("c");
        builder.getIntAndRemove("c", 0);
        builder.getLongAndRemove("c");
        builder.getLongAndRemove("c", 0);
        builder.getFloatAndRemove("c");
        builder.getFloatAndRemove("c", 0);
        builder.getDoubleAndRemove("c");
        builder.getDoubleAndRemove("c", 0);
        builder.getAllAndRemove("c");
        builder.getTimeMillisAndRemove("c");
        builder.getTimeMillisAndRemove("c", 0);
        builder.remove("c");
        builder.removeAndThen("c");

        assertThat(builder.build()).isSameAs(headers);
        assertThat(builder.delegate()).isNull();
    }

    @Test
    void empty() {
        final HttpHeaders headers = HttpHeaders.of("a", "b");
        assertThat(headers.toBuilder()
                          .clear()
                          .build()).isSameAs(DefaultHttpHeaders.EMPTY);
        assertThat(headers.toBuilder()
                          .endOfStream(true)
                          .clear()
                          .build()).isSameAs(DefaultHttpHeaders.EMPTY_EOS);
    }

    @Test
    void buildTwice() {
        final HttpHeadersBuilder builder = HttpHeaders.builder().add("foo", "bar");
        assertThat(builder.build()).isEqualTo(HttpHeaders.of("foo", "bar"));
        assertThat(builder.build()).isEqualTo(HttpHeaders.of("foo", "bar"));
    }

    @Test
    void testContentDisposition() {
        final HttpHeaders headers = HttpHeaders.builder()
                                               .set("Content-Disposition", "form-data; name=foo")
                                               .build();
        final ContentDisposition cd = headers.contentDisposition();
        assertThat(cd.type()).isEqualTo("form-data");
        assertThat(cd.name()).isEqualTo("foo");
    }
}
