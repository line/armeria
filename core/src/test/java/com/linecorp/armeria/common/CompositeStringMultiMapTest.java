/*
 * Copyright 2023 LINE Corporation
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

import java.time.Instant;
import java.util.AbstractMap.SimpleEntry;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import io.netty.util.AsciiString;

class CompositeStringMultiMapTest {

    @Test
    void get() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.get("k1")).isEqualTo("v1");
        assertThat(headers.get("k2")).isEqualTo("v2");
        assertThat(headers.get("k3")).isEqualTo("v3");
        assertThat(headers.get("k4")).isEqualTo("v4");
        assertThat(headers.get("k5")).isEqualTo("v5");
        assertThat(headers.get("k6")).isEqualTo("v6");
        assertThat(headers.get("dup")).isEqualTo("dup1");
        assertThat(headers.get("not_exist")).isNull();
        assertThat(headers.get("not_exist", "defaultValue")).isEqualTo("defaultValue");

        headers.add("dup", "dup3");
        assertThat(headers.get("dup")).isEqualTo("dup1");

        headers.remove("dup");
        assertThat(headers.get("dup")).isNull();

        headers.add("dup", "dup4");
        headers.add("dup", "dup5");
        assertThat(headers.get("dup")).isEqualTo("dup4");

        headers.remove("dup");
        assertThat(headers.get("dup")).isNull();

        headers.add("dup", "dup6");
        headers.add("dup", "dup7");
        assertThat(headers.get("dup")).isEqualTo("dup6");
    }

    @Test
    void getLast() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers
                = new CompositeHttpHeadersBase(HttpHeaders.of(),
                                               HttpHeaders.of("k1", "v1"),
                                               HttpHeaders.of("k1", "v2",
                                                              "k1", "v3"),
                                               HttpHeaders.of("k1", "v4",
                                                              "k1", "v5",
                                                              "k1", "v6"),
                                               HttpHeaders.of());
        assertThat(headers.getLast("k1")).isEqualTo("v6");
        assertThat(headers.getLast("not_exist")).isNull();
        assertThat(headers.getLast("not_exist", "defaultValue")).isEqualTo("defaultValue");

        headers.add("k1", "v7");
        assertThat(headers.getLast("k1")).isEqualTo("v7");

        headers.remove("k1");
        assertThat(headers.getLast("k1")).isNull();

        headers.add("k1", "v8");
        headers.add("k1", "v9");
        assertThat(headers.getLast("k1")).isEqualTo("v9");

        headers.remove("k1");
        assertThat(headers.getLast("k1")).isNull();

        headers.add("k1", "v10");
        headers.add("k1", "v11");
        assertThat(headers.getLast("k1")).isEqualTo("v11");
    }

    @Test
    void getAll() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1",
                                                            "k2", "v2",
                                                            "k2", "v3"),
                                             HttpHeaders.of("k3", "v4",
                                                            "k3", "v5"),
                                             HttpHeaders.of("k3", "v6"),
                                             HttpHeaders.of());
        headers.add("k3", "v7");
        headers.add("k4", "v8");
        headers.remove("k2");
        headers.add("k0", "v0");
        headers.add("k4", "v9");

        assertThat(headers.getAll("k0")).isEqualTo(ImmutableList.of("v0"));
        assertThat(headers.getAll("k1")).isEqualTo(ImmutableList.of("v1"));
        assertThat(headers.getAll("k2")).isEmpty();
        assertThat(headers.getAll("k3")).isEqualTo(ImmutableList.of("v4", "v5", "v6", "v7"));
        assertThat(headers.getAll("k4")).isEqualTo(ImmutableList.of("v8", "v9"));
        assertThat(headers.getAll("not_exist")).isEqualTo(ImmutableList.of());
    }

    @Test
    void getBoolean() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "true"),
                                             HttpHeaders.of("k2", "true",
                                                            "k3", "true"),
                                             HttpHeaders.of("k4", "false",
                                                            "k5", "false",
                                                            "k6", "false"),
                                             HttpHeaders.of("dup", "true"),
                                             HttpHeaders.of("dup", "false"),
                                             HttpHeaders.of("XXX", "xxx"),
                                             HttpHeaders.of());
        assertThat(headers.getBoolean("k1")).isTrue();
        assertThat(headers.getBoolean("k2")).isTrue();
        assertThat(headers.getBoolean("k3")).isTrue();
        assertThat(headers.getBoolean("k4")).isFalse();
        assertThat(headers.getBoolean("k5")).isFalse();
        assertThat(headers.getBoolean("k6")).isFalse();
        assertThat(headers.getBoolean("dup")).isTrue();
        assertThat(headers.getInt("XXX")).isNull();
        assertThat(headers.getBoolean("not_exist")).isNull();
        assertThat(headers.getBoolean("not_exist", true)).isTrue();
    }

    @Test
    void getLastBoolean() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "true"),
                                             HttpHeaders.of("k1", "true",
                                                            "k1", "true"),
                                             HttpHeaders.of("k1", "false",
                                                            "k1", "false",
                                                            "k1", "false"),
                                             HttpHeaders.of());
        assertThat(headers.getLastBoolean("k1")).isFalse();
        assertThat(headers.getLastBoolean("not_exist")).isNull();
        assertThat(headers.getLastBoolean("not_exist", true)).isTrue();
    }

    @Test
    void getInt() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "1"),
                                             HttpHeaders.of("k2", "2",
                                                            "k3", "3"),
                                             HttpHeaders.of("k4", "4",
                                                            "k5", "5",
                                                            "k6", "6"),
                                             HttpHeaders.of("dup", "100"),
                                             HttpHeaders.of("dup", "101"),
                                             HttpHeaders.of("XXX", "xxx"),
                                             HttpHeaders.of());
        assertThat(headers.getInt("k1")).isEqualTo(1);
        assertThat(headers.getInt("k2")).isEqualTo(2);
        assertThat(headers.getInt("k3")).isEqualTo(3);
        assertThat(headers.getInt("k4")).isEqualTo(4);
        assertThat(headers.getInt("k5")).isEqualTo(5);
        assertThat(headers.getInt("k6")).isEqualTo(6);
        assertThat(headers.getInt("dup")).isEqualTo(100);
        assertThat(headers.getInt("XXX")).isNull();
        assertThat(headers.getInt("not_exist")).isNull();
        assertThat(headers.getInt("not_exist", Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void getLastInt() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "1"),
                                             HttpHeaders.of("k1", "2",
                                                            "k1", "3"),
                                             HttpHeaders.of("k1", "4",
                                                            "k1", "5",
                                                            "k1", "6"),
                                             HttpHeaders.of());
        assertThat(headers.getLastInt("k1")).isEqualTo(6);
        assertThat(headers.getLastInt("not_exist")).isNull();
        assertThat(headers.getLastInt("not_exist", Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void getLong() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "1"),
                                             HttpHeaders.of("k2", "2",
                                                            "k3", "3"),
                                             HttpHeaders.of("k4", "4",
                                                            "k5", "5",
                                                            "k6", "6"),
                                             HttpHeaders.of("dup", "100"),
                                             HttpHeaders.of("dup", "101"),
                                             HttpHeaders.of("XXX", "xxx"),
                                             HttpHeaders.of());
        assertThat(headers.getLong("k1")).isEqualTo(1L);
        assertThat(headers.getLong("k2")).isEqualTo(2L);
        assertThat(headers.getLong("k3")).isEqualTo(3L);
        assertThat(headers.getLong("k4")).isEqualTo(4L);
        assertThat(headers.getLong("k5")).isEqualTo(5L);
        assertThat(headers.getLong("k6")).isEqualTo(6L);
        assertThat(headers.getLong("dup")).isEqualTo(100L);
        assertThat(headers.getLong("XXX")).isNull();
        assertThat(headers.getLong("not_exist")).isNull();
        assertThat(headers.getLong("not_exist", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void getLastLong() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "1"),
                                             HttpHeaders.of("k1", "2",
                                                            "k1", "3"),
                                             HttpHeaders.of("k1", "4",
                                                            "k1", "5",
                                                            "k1", "6"),
                                             HttpHeaders.of());
        assertThat(headers.getLastLong("k1")).isEqualTo(6L);
        assertThat(headers.getLastLong("not_exist")).isNull();
        assertThat(headers.getLastLong("not_exist", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void getFloat() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "1.0"),
                                             HttpHeaders.of("k2", "2.0",
                                                            "k3", "3.0"),
                                             HttpHeaders.of("k4", "4.0",
                                                            "k5", "5.0",
                                                            "k6", "6.0"),
                                             HttpHeaders.of("dup", "100.0"),
                                             HttpHeaders.of("dup", "101.0"),
                                             HttpHeaders.of("XXX", "xxx"),
                                             HttpHeaders.of());
        assertThat(headers.getFloat("k1")).isEqualTo(1.0f);
        assertThat(headers.getFloat("k2")).isEqualTo(2.0f);
        assertThat(headers.getFloat("k3")).isEqualTo(3.0f);
        assertThat(headers.getFloat("k4")).isEqualTo(4.0f);
        assertThat(headers.getFloat("k5")).isEqualTo(5.0f);
        assertThat(headers.getFloat("k6")).isEqualTo(6.0f);
        assertThat(headers.getFloat("dup")).isEqualTo(100.0f);
        assertThat(headers.getFloat("XXX")).isNull();
        assertThat(headers.getFloat("not_exist")).isNull();
        assertThat(headers.getFloat("not_exist", 123.0f)).isEqualTo(123.0f);
    }

    @Test
    void getLastFloat() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "1.0"),
                                             HttpHeaders.of("k1", "2.0",
                                                            "k1", "3.0"),
                                             HttpHeaders.of("k1", "4.0",
                                                            "k1", "5.0",
                                                            "k1", "6.0"),
                                             HttpHeaders.of());
        assertThat(headers.getLastFloat("k1")).isEqualTo(6.0f);
        assertThat(headers.getLastFloat("not_exist")).isNull();
        assertThat(headers.getLastFloat("not_exist", 123.0f)).isEqualTo(123.0f);
    }

    @Test
    void getDouble() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "1.0"),
                                             HttpHeaders.of("k2", "2.0",
                                                            "k3", "3.0"),
                                             HttpHeaders.of("k4", "4.0",
                                                            "k5", "5.0",
                                                            "k6", "6.0"),
                                             HttpHeaders.of("dup", "100.0"),
                                             HttpHeaders.of("dup", "101.0"),
                                             HttpHeaders.of("XXX", "xxx"),
                                             HttpHeaders.of());
        assertThat(headers.getDouble("k1")).isEqualTo(1.0);
        assertThat(headers.getDouble("k2")).isEqualTo(2.0);
        assertThat(headers.getDouble("k3")).isEqualTo(3.0);
        assertThat(headers.getDouble("k4")).isEqualTo(4.0);
        assertThat(headers.getDouble("k5")).isEqualTo(5.0);
        assertThat(headers.getDouble("k6")).isEqualTo(6.0);
        assertThat(headers.getDouble("dup")).isEqualTo(100.0);
        assertThat(headers.getDouble("XXX")).isNull();
        assertThat(headers.getDouble("not_exist")).isNull();
        assertThat(headers.getDouble("not_exist", 123.0)).isEqualTo(123.0);
    }

    @Test
    void getLastDouble() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "1.0"),
                                             HttpHeaders.of("k1", "2.0",
                                                            "k1", "3.0"),
                                             HttpHeaders.of("k1", "4.0",
                                                            "k1", "5.0",
                                                            "k1", "6.0"),
                                             HttpHeaders.of());
        assertThat(headers.getLastDouble("k1")).isEqualTo(6.0);
        assertThat(headers.getLastDouble("not_exist")).isNull();
        assertThat(headers.getLastDouble("not_exist", 123.0)).isEqualTo(123.0);
    }

    @Test
    void getTimeMillis() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers = new CompositeHttpHeadersBase(
                HttpHeaders.of(),
                HttpHeaders.of("k1", Date.from(Instant.ofEpochMilli(1000))),
                HttpHeaders.of("k2", Date.from(Instant.ofEpochMilli(2000)),
                               "k3", Date.from(Instant.ofEpochMilli(3000))),
                HttpHeaders.of("dup", Date.from(Instant.ofEpochMilli(4000))),
                HttpHeaders.of("dup", Date.from(Instant.ofEpochMilli(5000))),
                HttpHeaders.of("XXX", "xxx"),
                HttpHeaders.of()
        );
        assertThat(headers.getTimeMillis("k1")).isEqualTo(1000L);
        assertThat(headers.getTimeMillis("k2")).isEqualTo(2000L);
        assertThat(headers.getTimeMillis("k3")).isEqualTo(3000L);
        assertThat(headers.getTimeMillis("dup")).isEqualTo(4000L);
        assertThat(headers.getTimeMillis("XXX")).isNull();
        assertThat(headers.getTimeMillis("not_exist")).isNull();
        assertThat(headers.getLong("not_exist", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void getLastTimeMillis() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers = new CompositeHttpHeadersBase(
                HttpHeaders.of(),
                HttpHeaders.of("k1", Date.from(Instant.ofEpochMilli(1000))),
                HttpHeaders.of("k1", Date.from(Instant.ofEpochMilli(2000)),
                               "k1", Date.from(Instant.ofEpochMilli(3000))),
                HttpHeaders.of()
        );
        assertThat(headers.getLastTimeMillis("k1")).isEqualTo(3000L);
        assertThat(headers.getLastTimeMillis("not_exist")).isNull();
        assertThat(headers.getLastTimeMillis("not_exist", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void contains() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers = new CompositeHttpHeadersBase(
                HttpHeaders.of(),
                HttpHeaders.of("k1", "v1"),
                HttpHeaders.of("k2", "object",
                               "k3", "true"),
                HttpHeaders.of("k4", "100",
                               "k4", "200"),
                HttpHeaders.of("k4", "300.0",
                               "k4", "400.0"),
                HttpHeaders.of("k5", Date.from(Instant.ofEpochMilli(1000))),
                HttpHeaders.of()
        );
        assertThat(headers.contains("k1", "v1")).isTrue();
        headers.remove("k1");
        headers.add("k1", "v2");
        assertThat(headers.contains("k1", "v1")).isFalse();
        assertThat(headers.contains("k1", "v2")).isTrue();
        assertThat(headers.containsObject("k2", "object")).isTrue();
        assertThat(headers.containsBoolean("k3", true)).isTrue();
        assertThat(headers.containsInt("k4", 100)).isTrue();
        assertThat(headers.containsLong("k4", 200L)).isTrue();
        assertThat(headers.containsFloat("k4", 300.0f)).isTrue();
        assertThat(headers.containsDouble("k4", 400.0)).isTrue();
        assertThat(headers.containsTimeMillis("k5", 1000L)).isTrue();
    }

    @Test
    void size() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.size()).isEqualTo(8);

        headers.add("k1", "v1.a");
        headers.add("k7", "v7");
        headers.add("dup", "dup3");
        assertThat(headers.size()).isEqualTo(11);

        headers.remove("dup");
        assertThat(headers.size()).isEqualTo(8);

        headers.remove("k1");
        headers.remove("k2");
        headers.remove("k3");
        assertThat(headers.size()).isEqualTo(4);

        headers.remove("k4");
        headers.remove("k5");
        headers.remove("k6");
        headers.remove("k7");
        assertThat(headers.size()).isZero();
        assertThat(headers.isEmpty()).isTrue();
    }

    @Test
    void isEmpty() {
        final CompositeStringMultimap<CharSequence, AsciiString> empty1 =
                new CompositeHttpHeadersBase(HttpHeaders.of());
        assertThat(empty1.isEmpty()).isTrue();
        assertThat(empty1.size()).isZero();
        assertThat(empty1.get("not_exist")).isNull();
        assertThat(empty1.names()).isEmpty();

        final CompositeStringMultimap<CharSequence, AsciiString> empty2 =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of(),
                                             HttpHeaders.builder()
                                                        .add("k1", "v1")
                                                        .removeAndThen("k1")
                                                        .build(),
                                             HttpHeaders.builder()
                                                        .add("k1", "v1",
                                                             "k1", "v2")
                                                        .removeAndThen("k1")
                                                        .build(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2"),
                                             HttpHeaders.of("k3", "v3"),
                                             HttpHeaders.of());
        assertThat(empty2.isEmpty()).isFalse();

        empty2.remove("k1");
        empty2.remove("k2");
        empty2.remove("k3");
        assertThat(empty2.isEmpty()).isTrue();
        assertThat(empty2.size()).isZero();
        assertThat(empty2.get("not_exist")).isNull();
        assertThat(empty2.names()).isEmpty();
    }

    @Test
    void names() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        final Set<AsciiString> expected = ImmutableList
                .of("k1", "k2", "k3", "k4", "k5", "k6", "dup")
                .stream()
                .map(AsciiString::of)
                .collect(Collectors.toSet());
        assertThat(headers.names()).isEqualTo(expected);
        assertThat(headers.names().size()).isEqualTo(7);
    }

    @Test
    void names_removed() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        headers.add("add1", "v1");
        headers.remove("k2");
        headers.remove("k4");
        headers.add("add2", "v1");
        headers.remove("k6");
        headers.remove("dup");
        headers.add("add3", "v1");
        headers.remove("dup");

        final Set<AsciiString> expected = ImmutableList
                .of("add1", "add2", "add3", "k1", "k3", "k5")
                .stream()
                .map(AsciiString::of)
                .collect(Collectors.toSet());
        assertThat(headers.names()).isEqualTo(expected);
        assertThat(headers.names().size()).isEqualTo(6);
    }

    @Test
    void iterator() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        final Iterator<Entry<AsciiString, String>> iterator = headers.iterator();
        final ImmutableList.Builder<Entry<AsciiString, String>> builder = ImmutableList.builder();

        final List<Entry<AsciiString, String>> expected = builder
                .add(new SimpleEntry<>(AsciiString.of("k1"), "v1"),
                     new SimpleEntry<>(AsciiString.of("k2"), "v2"),
                     new SimpleEntry<>(AsciiString.of("k3"), "v3"),
                     new SimpleEntry<>(AsciiString.of("k4"), "v4"),
                     new SimpleEntry<>(AsciiString.of("k5"), "v5"),
                     new SimpleEntry<>(AsciiString.of("k6"), "v6"),
                     new SimpleEntry<>(AsciiString.of("dup"), "dup1"),
                     new SimpleEntry<>(AsciiString.of("dup"), "dup2"))
                .build();
        for (int i = 0; iterator.hasNext(); i++) {
            assertThat(iterator.next()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void iterator_removed() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        headers.remove("k2");
        headers.remove("k4");
        headers.add("k7", "v7");
        headers.remove("k6");
        headers.remove("dup");
        final Iterator<Entry<AsciiString, String>> iterator = headers.iterator();
        final ImmutableList.Builder<Entry<AsciiString, String>> builder = ImmutableList.builder();

        final List<Entry<AsciiString, String>> expected = builder
                .add(new SimpleEntry<>(AsciiString.of("k1"), "v1"),
                     new SimpleEntry<>(AsciiString.of("k3"), "v3"),
                     new SimpleEntry<>(AsciiString.of("k5"), "v5"),
                     new SimpleEntry<>(AsciiString.of("k7"), "v7"))
                .build();
        for (int i = 0; iterator.hasNext(); i++) {
            assertThat(iterator.next()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void valueIterator() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("X", "v2",
                                                            "k1", "v3"),
                                             HttpHeaders.of("X", "v4",
                                                            "k1", "v5",
                                                            "X", "v6"),
                                             HttpHeaders.of("k1", "dup1"),
                                             HttpHeaders.of("k1", "dup2"),
                                             HttpHeaders.of());
        final Iterator<String> valueIterator = headers.valueIterator("k1");

        final List<String> expected = ImmutableList.of("v1", "v3", "v5", "dup1", "dup2");
        for (int i = 0; valueIterator.hasNext(); i++) {
            assertThat(valueIterator.next()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void valueIterator_removed() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("X", "v2",
                                                            "k1", "v3"),
                                             HttpHeaders.of("X", "v4",
                                                            "k1", "v5",
                                                            "X", "v6"),
                                             HttpHeaders.of("k1", "dup1"),
                                             HttpHeaders.of("k1", "dup2"),
                                             HttpHeaders.of());
        headers.remove("k1");
        final Iterator<String> emptyIterator = headers.valueIterator("k1");
        assertThat(emptyIterator.hasNext()).isFalse();

        headers.add("k1", "v1");
        headers.add("k1", "v2");
        headers.add("k1", "v3");
        final Iterator<String> valueIterator = headers.valueIterator("k1");

        final List<String> expected = ImmutableList.of("v1", "v2", "v3");
        for (int i = 0; valueIterator.hasNext(); i++) {
            assertThat(valueIterator.next()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void forEach() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        headers.add("dup", "dup3");
        headers.remove("k2");
        headers.remove("k4");
        headers.remove("k6");
        headers.add("k7", "v7");
        final ImmutableList.Builder<Entry<AsciiString, String>> actual = ImmutableList.builder();
        headers.forEach((k, v) -> actual.add(new SimpleEntry<>(k, v)));

        final ImmutableList.Builder<Entry<AsciiString, String>> expected = ImmutableList.builder();
        expected.add(new SimpleEntry<>(AsciiString.of("k1"), "v1"),
                     new SimpleEntry<>(AsciiString.of("k3"), "v3"),
                     new SimpleEntry<>(AsciiString.of("k5"), "v5"),
                     new SimpleEntry<>(AsciiString.of("dup"), "dup1"),
                     new SimpleEntry<>(AsciiString.of("dup"), "dup2"),
                     new SimpleEntry<>(AsciiString.of("dup"), "dup3"),
                     new SimpleEntry<>(AsciiString.of("k7"), "v7"));
        assertThat(actual.build()).isEqualTo(expected.build());
    }

    @Test
    void forEachValue() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("X", "v2",
                                                            "k1", "v3"),
                                             HttpHeaders.of("X", "v4",
                                                            "k1", "v5",
                                                            "X", "v6"),
                                             HttpHeaders.of("k1", "dup1"),
                                             HttpHeaders.of("k1", "dup2"),
                                             HttpHeaders.of());
        final ImmutableList.Builder<String> builder1 = ImmutableList.builder();
        headers.forEachValue("k1", builder1::add);
        final List<String> expected1 = ImmutableList.of("v1", "v3", "v5", "dup1", "dup2");
        assertThat(builder1.build()).isEqualTo(expected1);

        headers.remove("k1");
        final ImmutableList.Builder<String> empty = ImmutableList.builder();
        headers.forEachValue("k1", empty::add);
        assertThat(empty.build()).isEmpty();

        headers.add("k1", "v1");
        headers.add("k1", "v2");
        headers.add("k1", "v3");
        final ImmutableList.Builder<String> builder2 = ImmutableList.builder();
        headers.forEachValue("k1", builder2::add);
        final List<String> expected2 = ImmutableList.of("v1", "v2", "v3");
        assertThat(builder2.build()).isEqualTo(expected2);
    }

    @Test
    void getAndRemove() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k1", "v4",
                                                            "k2", "v5",
                                                            "k3", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.getAndRemove("k1")).isEqualTo("v1");
        assertThat(headers.get("k1")).isNull();
        assertThat(headers.contains("k1")).isFalse();
        assertThat(headers.size()).isEqualTo(6);

        assertThat(headers.getAndRemove("k2")).isEqualTo("v2");
        assertThat(headers.get("k2")).isNull();
        assertThat(headers.contains("k2")).isFalse();
        assertThat(headers.size()).isEqualTo(4);

        assertThat(headers.getAndRemove("k3")).isEqualTo("v3");
        assertThat(headers.get("k3")).isNull();
        assertThat(headers.contains("k3")).isFalse();
        assertThat(headers.size()).isEqualTo(2);

        assertThat(headers.getAndRemove("dup")).isEqualTo("dup1");
        assertThat(headers.get("dup")).isNull();
        assertThat(headers.contains("dup")).isFalse();
        assertThat(headers.size()).isEqualTo(0);
        assertThat(headers.isEmpty()).isTrue();

        assertThat(headers.getAndRemove("k1", "defaultValue")).isEqualTo("defaultValue");
    }

    @Test
    void getAllAndRemove() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k1", "v4",
                                                            "k2", "v5",
                                                            "k3", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.getAllAndRemove("k1")).isEqualTo(ImmutableList.of("v1", "v4"));
        assertThat(headers.get("k1")).isNull();
        assertThat(headers.contains("k1")).isFalse();
        assertThat(headers.size()).isEqualTo(6);

        assertThat(headers.getAllAndRemove("k2")).isEqualTo(ImmutableList.of("v2", "v5"));
        assertThat(headers.get("k2")).isNull();
        assertThat(headers.contains("k2")).isFalse();
        assertThat(headers.size()).isEqualTo(4);

        assertThat(headers.getAllAndRemove("k3")).isEqualTo(ImmutableList.of("v3", "v6"));
        assertThat(headers.get("k3")).isNull();
        assertThat(headers.contains("k3")).isFalse();
        assertThat(headers.size()).isEqualTo(2);

        assertThat(headers.getAllAndRemove("dup")).isEqualTo(ImmutableList.of("dup1", "dup2"));
        assertThat(headers.get("dup")).isNull();
        assertThat(headers.contains("dup")).isFalse();
        assertThat(headers.size()).isEqualTo(0);
        assertThat(headers.isEmpty()).isTrue();

        assertThat(headers.getAllAndRemove("k1")).isEmpty();
    }

    @Test
    void getTypeAndRemove() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers = new CompositeHttpHeadersBase(
                HttpHeaders.of(),
                HttpHeaders.of("k1", "100",
                               "k1", "101",
                               "k2", "200",
                               "k2", "201"),
                HttpHeaders.of("k3", "300.0",
                               "k3", "301.0",
                               "k4", "400.0",
                               "k4", "401.0"),
                HttpHeaders.of("k5", Date.from(Instant.ofEpochMilli(1000)),
                               "k5", Date.from(Instant.ofEpochMilli(2000))),
                HttpHeaders.of()
        );
        assertThat(headers.getIntAndRemove("k1")).isEqualTo(100);
        assertThat(headers.getIntAndRemove("k1", Integer.MAX_VALUE)).isEqualTo(Integer.MAX_VALUE);
        assertThat(headers.getLongAndRemove("k2")).isEqualTo(200L);
        assertThat(headers.getLongAndRemove("k2", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
        assertThat(headers.getFloatAndRemove("k3")).isEqualTo(300.0f);
        assertThat(headers.getFloatAndRemove("k3", 123.0f)).isEqualTo(123.0f);
        assertThat(headers.getDoubleAndRemove("k4")).isEqualTo(400.0);
        assertThat(headers.getDoubleAndRemove("k4", 123.0)).isEqualTo(123.0);
        assertThat(headers.getTimeMillisAndRemove("k5")).isEqualTo(1000);
        assertThat(headers.getTimeMillisAndRemove("k5", Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void add() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of());
        headers.add("k1", "v1.a");
        headers.add("k1", "v1.b");
        headers.add("k2", ImmutableList.of("v2.a", "v2.b"));
        headers.add("k3", "v3.a", "v3.b");
        headers.add(ImmutableList.of(new SimpleEntry<>("k4", "v4.a"), new SimpleEntry<>("k4", "v4.b")));
        headers.addObject("k5", "v5.a");
        headers.addObject("k5", "v5.b");
        headers.addObject("k6", ImmutableList.of("v6.a", "v6.b"));
        headers.addObject("k7", "v7.a", "v7.b");
        headers.addObject(ImmutableList.of(new SimpleEntry<>("k8", "v8.a"), new SimpleEntry<>("k8", "v8.b")));

        assertThat(headers.getAll("k1")).isEqualTo(ImmutableList.of("v1.a", "v1.b"));
        assertThat(headers.getAll("k2")).isEqualTo(ImmutableList.of("v2.a", "v2.b"));
        assertThat(headers.getAll("k3")).isEqualTo(ImmutableList.of("v3.a", "v3.b"));
        assertThat(headers.getAll("k4")).isEqualTo(ImmutableList.of("v4.a", "v4.b"));
        assertThat(headers.getAll("k5")).isEqualTo(ImmutableList.of("v5.a", "v5.b"));
        assertThat(headers.getAll("k6")).isEqualTo(ImmutableList.of("v6.a", "v6.b"));
        assertThat(headers.getAll("k7")).isEqualTo(ImmutableList.of("v7.a", "v7.b"));
        assertThat(headers.getAll("k8")).isEqualTo(ImmutableList.of("v8.a", "v8.b"));
        assertThat(headers.size()).isEqualTo(16);
    }

    @Test
    void addType() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of());
        headers.addInt("k1", 100);
        headers.addLong("k2", 200L);
        headers.addFloat("k3", 300.0f);
        headers.addDouble("k4", 400.0);
        headers.addTimeMillis("k5", Date.from(Instant.ofEpochMilli(1000)).getTime());

        assertThat(headers.getInt("k1")).isEqualTo(100);
        assertThat(headers.getLong("k2")).isEqualTo(200L);
        assertThat(headers.getFloat("k3")).isEqualTo(300.0f);
        assertThat(headers.getDouble("k4")).isEqualTo(400.0);
        assertThat(headers.getTimeMillis("k5")).isEqualTo(1000L);
        assertThat(headers.size()).isEqualTo(5);
    }

    @Test
    void set() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of());
        headers.set("k1", "v1.a");
        headers.set("k2", ImmutableList.of("v2.a", "v2.b"));
        headers.set("k3", "v3.a", "v3.b");
        headers.set(ImmutableList.of(new SimpleEntry<>("k4", "v4.a"), new SimpleEntry<>("k4", "v4.b")));
        headers.setObject("k5", "v5.a");
        headers.setObject("k6", ImmutableList.of("v6.a", "v6.b"));
        headers.setObject("k7", "v7.a", "v7.b");
        headers.setObject(ImmutableList.of(new SimpleEntry<>("k8", "v8.a"), new SimpleEntry<>("k8", "v8.b")));

        assertThat(headers.getAll("k1")).isEqualTo(ImmutableList.of("v1.a"));
        assertThat(headers.getAll("k2")).isEqualTo(ImmutableList.of("v2.a", "v2.b"));
        assertThat(headers.getAll("k3")).isEqualTo(ImmutableList.of("v3.a", "v3.b"));
        assertThat(headers.getAll("k4")).isEqualTo(ImmutableList.of("v4.a", "v4.b"));
        assertThat(headers.getAll("k5")).isEqualTo(ImmutableList.of("v5.a"));
        assertThat(headers.getAll("k6")).isEqualTo(ImmutableList.of("v6.a", "v6.b"));
        assertThat(headers.getAll("k7")).isEqualTo(ImmutableList.of("v7.a", "v7.b"));
        assertThat(headers.getAll("k8")).isEqualTo(ImmutableList.of("v8.a", "v8.b"));
        assertThat(headers.size()).isEqualTo(14);

        headers.set("k1", "v1.b");
        headers.set("k2", ImmutableList.of("v2.c", "v2.d"));
        headers.set("k3", "v3.c", "v3.d");
        headers.set(ImmutableList.of(new SimpleEntry<>("k4", "v4.c"), new SimpleEntry<>("k4", "v4.d")));
        headers.setObject("k5", "v5.b");
        headers.setObject("k6", ImmutableList.of("v6.c", "v6.d"));
        headers.setObject("k7", "v7.c", "v7.d");
        headers.setObject(ImmutableList.of(new SimpleEntry<>("k8", "v8.c"), new SimpleEntry<>("k8", "v8.d")));

        assertThat(headers.getAll("k1")).isEqualTo(ImmutableList.of("v1.b"));
        assertThat(headers.getAll("k2")).isEqualTo(ImmutableList.of("v2.c", "v2.d"));
        assertThat(headers.getAll("k3")).isEqualTo(ImmutableList.of("v3.c", "v3.d"));
        assertThat(headers.getAll("k4")).isEqualTo(ImmutableList.of("v4.c", "v4.d"));
        assertThat(headers.getAll("k5")).isEqualTo(ImmutableList.of("v5.b"));
        assertThat(headers.getAll("k6")).isEqualTo(ImmutableList.of("v6.c", "v6.d"));
        assertThat(headers.getAll("k7")).isEqualTo(ImmutableList.of("v7.c", "v7.d"));
        assertThat(headers.getAll("k8")).isEqualTo(ImmutableList.of("v8.c", "v8.d"));
        assertThat(headers.size()).isEqualTo(14);
    }

    @Test
    void setIfAbsent() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        headers.remove("dup");
        headers.remove("k3");
        headers.add("k3", "v3.a");
        headers.setIfAbsent(ImmutableList.of(new SimpleEntry<>("dup", "dup3"),
                                             new SimpleEntry<>("dup", "dup4"),
                                             new SimpleEntry<>("k1", "v1.a"),
                                             new SimpleEntry<>("k3", "v3.b"),
                                             new SimpleEntry<>("k7", "v7.a"),
                                             new SimpleEntry<>("k8", "v8.a"),
                                             new SimpleEntry<>("k7", "v7.b"),
                                             new SimpleEntry<>("k8", "v8.b"),
                                             new SimpleEntry<>("k9", "v9")));

        assertThat(headers.size()).isEqualTo(13);
        assertThat(headers.get("k1")).isEqualTo("v1");
        assertThat(headers.get("k2")).isEqualTo("v2");
        assertThat(headers.get("k3")).isEqualTo("v3.a");
        assertThat(headers.get("k4")).isEqualTo("v4");
        assertThat(headers.get("k5")).isEqualTo("v5");
        assertThat(headers.get("k6")).isEqualTo("v6");
        assertThat(headers.getAll("dup")).isEqualTo(ImmutableList.of("dup3", "dup4"));
        assertThat(headers.getAll("k7")).isEqualTo(ImmutableList.of("v7.a", "v7.b"));
        assertThat(headers.getAll("k8")).isEqualTo(ImmutableList.of("v8.a", "v8.b"));
        assertThat(headers.get("k9")).isEqualTo("v9");
    }

    @Test
    void setType() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of());
        headers.setInt("k1", 100);
        headers.setLong("k2", 200L);
        headers.setFloat("k3", 300.0f);
        headers.setDouble("k4", 400.0);
        headers.setTimeMillis("k5", Date.from(Instant.ofEpochMilli(1000)).getTime());

        assertThat(headers.getInt("k1")).isEqualTo(100);
        assertThat(headers.getLong("k2")).isEqualTo(200L);
        assertThat(headers.getFloat("k3")).isEqualTo(300.0f);
        assertThat(headers.getDouble("k4")).isEqualTo(400.0);
        assertThat(headers.getTimeMillis("k5")).isEqualTo(1000L);
        assertThat(headers.size()).isEqualTo(5);
    }

    @Test
    void remove() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        headers.add("add", "add");
        headers.remove("add");
        assertThat(headers.get("add")).isNull();
        assertThat(headers.contains("add")).isFalse();
        assertThat(headers.size()).isEqualTo(8);

        assertThat(headers.remove("not_exist")).isFalse();
        assertThat(headers.size()).isEqualTo(8);

        assertThat(headers.remove("k1")).isTrue();
        assertThat(headers.remove("k2")).isTrue();
        assertThat(headers.remove("k3")).isTrue();
        assertThat(headers.remove("k4")).isTrue();
        assertThat(headers.remove("k5")).isTrue();
        assertThat(headers.remove("k6")).isTrue();
        assertThat(headers.size()).isEqualTo(2);

        assertThat(headers.remove("dup")).isTrue();
        assertThat(headers.isEmpty()).isTrue();

        headers.add("k1", "v1");
        headers.remove("k1");
        assertThat(headers.isEmpty()).isTrue();

        headers.add("k1", "v1");
        headers.remove("k1");
        headers.remove("k1");
        assertThat(headers.isEmpty()).isTrue();
    }

    @Test
    void clear() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        headers.clear();
        assertThat(headers.isEmpty()).isTrue();
    }

    @Test
    void equals_true() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.equals(headers)).isTrue();

        final CompositeStringMultimap<CharSequence, AsciiString> other1 =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.equals(other1)).isTrue();

        final CompositeStringMultimap<CharSequence, AsciiString> other2 =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("diff1", "diff1"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("diff2", "diff2"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of());
        other2.remove("diff1");
        other2.add("dup", "dup2");
        other2.remove("diff2");
        assertThat(headers.equals(other2)).isTrue();

        final HttpHeaders other3 = HttpHeaders.builder()
                                              .add("k1", "v1")
                                              .add("k2", "v2")
                                              .add("k3", "v3")
                                              .add("k4", "v4")
                                              .add("k5", "v5")
                                              .add("k6", "v6")
                                              .add("dup", "dup1")
                                              .add("dup", "dup2")
                                              .build();
        assertThat(headers.equals(other3)).isTrue();

        final HttpHeaderGetters other4 = HttpHeaders.builder()
                                                    .add("k1", "v1")
                                                    .add("k2", "v2")
                                                    .add("k3", "v3")
                                                    .add("k4", "v4")
                                                    .add("k5", "v5")
                                                    .add("k6", "v6")
                                                    .add("dup", "dup1")
                                                    .add("dup", "dup2");
        assertThat(headers.equals(other4)).isTrue();
    }

    @Test
    void equals_true_insertionOrder() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.equals(headers)).isTrue();

        final CompositeStringMultimap<CharSequence, AsciiString> other1 =
                new CompositeHttpHeadersBase(HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("diff1", "diff1"),
                                             HttpHeaders.of(),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("diff2", "diff2"));
        other1.remove("diff1");
        other1.add("dup", "dup2");
        other1.remove("diff2");
        assertThat(headers.equals(other1)).isTrue();

        final HttpHeaders other2 = HttpHeaders.builder()
                                              .add("k4", "v4")
                                              .add("k5", "v5")
                                              .add("k6", "v6")
                                              .add("k1", "v1")
                                              .add("k2", "v2")
                                              .add("k3", "v3")
                                              .add("dup", "dup1")
                                              .add("dup", "dup2")
                                              .build();
        assertThat(headers.equals(other2)).isTrue();

        final HttpHeaderGetters other3 = HttpHeaders.builder()
                                                    .add("dup", "dup1")
                                                    .add("dup", "dup2")
                                                    .add("k2", "v2")
                                                    .add("k1", "v1")
                                                    .add("k4", "v4")
                                                    .add("k3", "v3")
                                                    .add("k6", "v6")
                                                    .add("k5", "v5");
        assertThat(headers.equals(other3)).isTrue();
    }

    @Test
    void equals_true_empty() {
        final CompositeStringMultimap<CharSequence, AsciiString> empty =
                new CompositeHttpHeadersBase(HttpHeaders.of(), HttpHeaders.of());

        assertThat(empty.equals(HttpHeaders.of())).isTrue();
        assertThat(empty.equals(HttpHeaders.builder().build())).isTrue();
        assertThat(empty.equals(HttpHeaders.builder())).isTrue();
    }

    @Test
    void equals_false() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.equals(null)).isFalse();

        final CompositeStringMultimap<CharSequence, AsciiString> other1 =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of());
        assertThat(headers.equals(other1)).isFalse();

        final HttpHeaders other2 = HttpHeaders.builder()
                                              .add("k1", "v1")
                                              .add("k2", "v2")
                                              .add("k3", "v3")
                                              .build();
        assertThat(headers.equals(other2)).isFalse();

        final HttpHeaderGetters other3 = HttpHeaders.builder()
                                                    .add("k1", "v1")
                                                    .add("k2", "v2")
                                                    .add("k3", "v3");
        assertThat(headers.equals(other3)).isFalse();
    }

    @Test
    void equals_false_insertionOrder() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        final CompositeStringMultimap<CharSequence, AsciiString> other1 =
                new CompositeHttpHeadersBase(HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("diff1", "diff1"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("diff2", "diff2"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of());
        assertThat(headers.equals(other1)).isFalse();

        other1.remove("dup");
        other1.remove("diff1");
        other1.remove("diff2");
        other1.add("dup", "dup1");
        other1.add("dup", "dup2");
        assertThat(headers.equals(other1)).isTrue();

        final HttpHeaders other2 = HttpHeaders.builder()
                                              .add("k1", "v1")
                                              .add("k2", "v2")
                                              .add("dup", "dup2")
                                              .add("k3", "v3")
                                              .add("k4", "v4")
                                              .add("dup", "dup1")
                                              .add("k5", "v5")
                                              .add("k6", "v6")
                                              .build();
        assertThat(headers.equals(other2)).isFalse();

        final HttpHeaderGetters other3 = HttpHeaders.builder()
                                                    .add("k1", "v1")
                                                    .add("dup", "dup2")
                                                    .add("k2", "v2")
                                                    .add("k3", "v3")
                                                    .add("k4", "v4")
                                                    .add("k5", "v5")
                                                    .add("k6", "v6")
                                                    .add("dup", "dup1");
        assertThat(headers.equals(other3)).isFalse();
    }

    @Test
    void equals_false_empty() {
        final CompositeStringMultimap<CharSequence, AsciiString> empty =
                new CompositeHttpHeadersBase(HttpHeaders.of(), HttpHeaders.of());

        assertThat(empty.equals(HttpHeaders.of("k1", "v1"))).isFalse();
        assertThat(empty.equals(HttpHeaders.builder().add("k1", "v1").build())).isFalse();
        assertThat(empty.equals(HttpHeaders.builder().add("k1", "v1"))).isFalse();
    }

    @Test
    void testToString() {
        final CompositeStringMultimap<CharSequence, AsciiString> headers =
                new CompositeHttpHeadersBase(HttpHeaders.of(),
                                             HttpHeaders.of("k1", "v1"),
                                             HttpHeaders.of("k2", "v2",
                                                            "k3", "v3"),
                                             HttpHeaders.of("k4", "v4",
                                                            "k5", "v5",
                                                            "k6", "v6"),
                                             HttpHeaders.of("dup", "dup1"),
                                             HttpHeaders.of("dup", "dup2"),
                                             HttpHeaders.of());
        assertThat(headers.toString())
                .isEqualTo("[k1=v1, k2=v2, k3=v3, k4=v4, k5=v5, k6=v6, dup=dup1, dup=dup2]");

        headers.remove("k2");
        headers.remove("k4");
        headers.add("k7", "v7");
        headers.remove("k6");
        headers.remove("dup");
        assertThat(headers.toString()).isEqualTo("[k1=v1, k3=v3, k5=v5, k7=v7]");

        headers.clear();
        assertThat(headers.toString()).isEqualTo("[]");
        assertThat(new CompositeHttpHeadersBase(HttpHeaders.of()).toString()).isEqualTo("[]");
    }
}
