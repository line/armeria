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

package com.linecorp.armeria.common.logback;

import static com.google.common.collect.ImmutableMap.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Iterator;
import java.util.Map.Entry;

import org.junit.jupiter.api.Test;

class UnionMapTest {

    @Test
    void testSize() {
        assertThat(new UnionMap<>(of("1", "a"), of("2", "b"))).hasSize(2);
        assertThat(new UnionMap<>(of("1", "a", "2", "b"), of("1", "b"))).hasSize(2);
        assertThat(new UnionMap<>(of("1", "a"), of("1", "b", "2", "b"))).hasSize(2);
    }

    @Test
    void testIsEmpty() {
        assertThat(new UnionMap<String, String>(of(), of())).isEmpty();
        assertThat(new UnionMap<>(of(), of("1", "a"))).isNotEmpty();
        assertThat(new UnionMap<>(of("1", "a"), of())).isNotEmpty();
    }

    @Test
    void testContainsKey() {
        assertThat(new UnionMap<>(of("1", "a"), of("2", "b"))).containsKey("1");
        assertThat(new UnionMap<>(of("1", "a"), of("2", "b"))).containsKey("2");
    }

    @Test
    void testContainsValue() {
        assertThat(new UnionMap<>(of("1", "a"), of("2", "b"))).containsValue("a");
        assertThat(new UnionMap<>(of("1", "a"), of("2", "b"))).containsValue("b");
    }

    @Test
    void testGet() {
        assertThat(new UnionMap<>(of("1", "a"), of("2", "b")).get("1")).isEqualTo("a");
        assertThat(new UnionMap<>(of("1", "a"), of("2", "b")).get("2")).isEqualTo("b");
        assertThat(new UnionMap<>(of("1", "a"), of("1", "b")).get("1")).isEqualTo("a");
    }

    @Test
    void testEntrySet() {
        assertThat(new UnionMap<>(of("1", "a"), of("2", "b"))).containsExactlyEntriesOf(of("1", "a", "2", "b"));
        assertThat(new UnionMap<>(of("1", "a"), of("2", "a"))).containsExactlyEntriesOf(of("1", "a", "2", "a"));
        assertThat(new UnionMap<>(of("1", "a"), of("1", "b"))).containsExactlyEntriesOf(of("1", "a"));
    }

    @Test
    void testImmutability() {
        final UnionMap<String, String> map = new UnionMap<>(of("1", "a"), of("2", "b"));

        assertThatThrownBy(() -> map.put("foo", "bar"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> map.remove("foo"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(map::clear)
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> {
            final Iterator<Entry<String, String>> i = map.entrySet().iterator();
            i.next();
            i.remove();
        }).isInstanceOf(UnsupportedOperationException.class);
    }
}
