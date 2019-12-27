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

class DefaultQueryParamsBuilderTest {

    @Test
    void add() {
        final QueryParams headers = QueryParams.builder()
                                               .add("a", "b")
                                               .add("c", ImmutableList.of("d", "e"))
                                               .add("f", "g", "h")
                                               .add(ImmutableMap.of("i", "j").entrySet())
                                               .build();
        assertThat(headers).containsExactly(
                Maps.immutableEntry("a", "b"),
                Maps.immutableEntry("c", "d"),
                Maps.immutableEntry("c", "e"),
                Maps.immutableEntry("f", "g"),
                Maps.immutableEntry("f", "h"),
                Maps.immutableEntry("i", "j"));
    }

    @Test
    void set() {
        final QueryParams headers = QueryParams.builder()
                                               .add("a", "b")
                                               .add("c", ImmutableList.of("d", "e"))
                                               .add("f", "g", "h")
                                               .add(ImmutableMap.of("i", "j").entrySet())
                                               .set("a", "B")
                                               .set("c", ImmutableList.of("D", "E"))
                                               .set("f", "G", "H")
                                               .set(ImmutableMap.of("i", "J").entrySet())
                                               .build();
        assertThat(headers).containsExactly(
                Maps.immutableEntry("a", "B"),
                Maps.immutableEntry("c", "D"),
                Maps.immutableEntry("c", "E"),
                Maps.immutableEntry("f", "G"),
                Maps.immutableEntry("f", "H"),
                Maps.immutableEntry("i", "J"));
    }

    @Test
    void mutation() {
        final QueryParams headers = QueryParams.of("a", "b");
        final QueryParams headers2 = headers.toBuilder().set("a", "c").build();
        assertThat(headers).isNotSameAs(headers2);
        assertThat(headers).isNotEqualTo(headers2);
        assertThat(headers).containsExactly(Maps.immutableEntry("a", "b"));
        assertThat(headers2).containsExactly(Maps.immutableEntry("a", "c"));
    }

    @Test
    void mutationAfterBuild() {
        final QueryParams headers = QueryParams.of("a", "b");
        final DefaultQueryParamsBuilder builder = (DefaultQueryParamsBuilder) headers.toBuilder();

        // Initial state
        assertThat(builder.parent()).isSameAs(headers);
        assertThat(builder.delegate()).isNull();

        // 1st mutation
        builder.add("c", "d");
        assertThat(builder.parent()).isSameAs(headers);
        assertThat(builder.delegate()).isNotNull().isNotSameAs(builder.parent());

        // 1st promotion
        QueryParamsBase oldDelegate = builder.delegate();
        final QueryParams headers2 = builder.build();
        assertThat(headers2).isNotSameAs(headers);
        assertThat(((QueryParamsBase) headers2).entries).isNotSameAs(((QueryParamsBase) headers).entries);
        assertThat(builder.parent()).isSameAs(oldDelegate);
        assertThat(builder.delegate()).isNull();

        // 2nd mutation
        builder.add("e", "f");
        assertThat(builder.parent()).isSameAs(oldDelegate);
        assertThat(builder.delegate()).isNotNull().isNotSameAs(builder.parent());

        // 2nd promotion
        oldDelegate = builder.delegate();
        final QueryParams headers3 = builder.build();
        assertThat(headers3).isNotSameAs(headers);
        assertThat(headers3).isNotSameAs(headers2);
        assertThat(((QueryParamsBase) headers3).entries).isNotSameAs(((QueryParamsBase) headers).entries);
        assertThat(((QueryParamsBase) headers3).entries).isNotSameAs(((QueryParamsBase) headers2).entries);
        assertThat(builder.parent()).isSameAs(oldDelegate);
        assertThat(builder.delegate()).isNull();

        // 3rd mutation, to make sure it doesn't affect the previously built headers.
        builder.clear();

        // Check the content.
        assertThat(headers).isNotSameAs(headers3);
        assertThat(headers).containsExactly(Maps.immutableEntry("a", "b"));
        assertThat(headers2).containsExactly(Maps.immutableEntry("a", "b"),
                                             Maps.immutableEntry("c", "d"));
        assertThat(headers3).containsExactly(Maps.immutableEntry("a", "b"),
                                             Maps.immutableEntry("c", "d"),
                                             Maps.immutableEntry("e", "f"));
    }

    @Test
    void noMutationNoCopy() {
        final QueryParams headers = QueryParams.of("a", "b");
        assertThat(headers.toBuilder().build()).isSameAs(headers);

        // Failed removal should not make a copy.
        final DefaultQueryParamsBuilder builder = (DefaultQueryParamsBuilder) headers.toBuilder();
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
        final QueryParams headers = QueryParams.of("a", "b");
        assertThat(headers.toBuilder()
                          .clear()
                          .build()).isSameAs(DefaultQueryParams.EMPTY);
    }

    @Test
    void buildTwice() {
        final QueryParamsBuilder builder = QueryParams.builder().add("foo", "bar");
        assertThat(builder.build()).isEqualTo(QueryParams.of("foo", "bar"));
        assertThat(builder.build()).isEqualTo(QueryParams.of("foo", "bar"));
    }
}
