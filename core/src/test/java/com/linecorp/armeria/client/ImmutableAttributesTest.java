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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.Attributes;
import com.linecorp.armeria.common.AttributesBuilder;
import com.linecorp.armeria.common.ConcurrentAttributes;

import io.netty.util.AttributeKey;

class ImmutableAttributesTest {

    @Test
    void get() {
        final AttributeKey<Integer> foo = AttributeKey.valueOf("foo");
        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        final Attributes attributes = Attributes.of(foo, 0, bar, "bar");
        assertThat(attributes.attr(foo)).isEqualTo(0);
        assertThat(attributes.attr(bar)).isEqualTo("bar");
    }

    @Test
    void set() {
        final AttributeKey<Integer> key = AttributeKey.valueOf("foo");
        final AttributesBuilder builder0 = Attributes.builder();
        final Attributes attributes0 = builder0.set(key, 0).build();
        assertThat(attributes0.attr(key)).isEqualTo(0);
        builder0.set(key, 1);
        assertThat(attributes0.attr(key)).isEqualTo(0);
        final Attributes attributes1 = builder0.build();
        assertThat(attributes1.attr(key)).isEqualTo(1);

        final AttributesBuilder builder2 = attributes0.toBuilder();
        builder2.set(key, 2);
        assertThat(attributes0.attr(key)).isEqualTo(0);
        final Attributes attributes2 = builder2.build();
        assertThat(attributes2.attr(key)).isEqualTo(2);
        builder2.remove(key);
        assertThat(attributes2.attr(key)).isEqualTo(2);
    }

    @Test
    void mutate() {
        final AttributeKey<Integer> key = AttributeKey.valueOf("foo");
        final Attributes attributes0 = Attributes.of(key, 0);
        final Attributes attributes1 = attributes0.withMutations(mutator -> mutator.set(key, 1));
        assertThat(attributes0.attr(key)).isZero();
        assertThat(attributes1.attr(key)).isOne();
    }

    @Test
    void parent() {
        final AttributeKey<String> removed = AttributeKey.valueOf("removed");
        final AttributeKey<String> overridden = AttributeKey.valueOf("overridden");
        final AttributeKey<String> childOnly = AttributeKey.valueOf("childOnly");
        final AttributeKey<String> parentOnly = AttributeKey.valueOf("parentOnly");
        final AttributeKey<String> shaded = AttributeKey.valueOf("shaded");

        final Attributes parent = Attributes.of(removed, "removed", overridden, "overridden",
                                                parentOnly, "parentOnly", shaded, "shaded");

        final Attributes attributes = Attributes.builder(parent)
                                                .set(overridden, "update")
                                                .removeAndThen(removed)
                                                .set(childOnly, "new")
                                                .set(shaded, null)
                                                .build();

        assertThat(attributes.parent()).isSameAs(parent);

        // Should not mutate the parent.
        assertThat(parent.attr(removed)).isEqualTo("removed");
        assertThat(parent.attr(overridden)).isEqualTo("overridden");
        assertThat(parent.attr(childOnly)).isNull();
        assertThat(parent.attr(shaded)).isEqualTo("shaded");

        // See the value in the parent.
        assertThat(attributes.attr(removed)).isEqualTo("removed");
        assertThat(attributes.ownAttr(removed)).isNull();
        // Hide the value in the parent
        assertThat(attributes.attr(shaded)).isNull();
        assertThat(attributes.ownAttr(shaded)).isNull();

        assertThat(attributes.attr(overridden)).isEqualTo("update");
        assertThat(attributes.ownAttr(overridden)).isEqualTo("update");
        assertThat(attributes.attr(childOnly)).isEqualTo("new");
        assertThat(attributes.ownAttr(childOnly)).isEqualTo("new");

        assertThat(attributes.hasAttr(removed)).isTrue();
        assertThat(attributes.hasOwnAttr(removed)).isFalse();

        assertThat(ImmutableList.copyOf(attributes.attrs()))
                .containsExactlyInAnyOrder(Maps.immutableEntry(removed, "removed"),
                                           Maps.immutableEntry(overridden, "update"),
                                           Maps.immutableEntry(childOnly, "new"),
                                           Maps.immutableEntry(parentOnly, "parentOnly"));

        assertThat(ImmutableList.copyOf(attributes.ownAttrs()))
                .containsExactlyInAnyOrder(Maps.immutableEntry(overridden, "update"),
                                           Maps.immutableEntry(childOnly, "new"));

        final AttributeKey<String> added = AttributeKey.valueOf("added");
        final Attributes newAttributes = attributes.toBuilder()
                                                   .set(added, "added")
                                                   .set(shaded, "revive")
                                                   .build();

        assertThat(ImmutableList.copyOf(newAttributes.attrs()))
                .containsExactlyInAnyOrder(Maps.immutableEntry(removed, "removed"),
                                           Maps.immutableEntry(overridden, "update"),
                                           Maps.immutableEntry(childOnly, "new"),
                                           Maps.immutableEntry(parentOnly, "parentOnly"),
                                           Maps.immutableEntry(shaded, "revive"),
                                           Maps.immutableEntry(added, "added"));

        assertThat(ImmutableList.copyOf(newAttributes.ownAttrs()))
                .containsExactlyInAnyOrder(Maps.immutableEntry(overridden, "update"),
                                           Maps.immutableEntry(childOnly, "new"),
                                           Maps.immutableEntry(shaded, "revive"),
                                           Maps.immutableEntry(added, "added"));
    }

    @Test
    void remove() {
        final AttributeKey<Integer> foo = AttributeKey.valueOf("foo");
        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        final Attributes attributes0 = Attributes.of(foo, 0, bar, "bar");
        final Attributes attributes1 = attributes0.toBuilder()
                                                  .removeAndThen(bar)
                                                  .build();

        assertThat(ImmutableList.copyOf(attributes0.attrs()))
                .containsExactlyInAnyOrder(Maps.immutableEntry(foo, 0), Maps.immutableEntry(bar, "bar"));

        assertThat(ImmutableList.copyOf(attributes1.attrs()))
                .containsExactly(Maps.immutableEntry(foo, 0));
    }

    @Test
    void iterator() {
        final AttributeKey<Integer> foo = AttributeKey.valueOf("foo");
        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        final Attributes attributes = Attributes.of(foo, 0, bar, "bar");
        assertThat(ImmutableList.copyOf(attributes.attrs()))
                .containsExactlyInAnyOrder(Maps.immutableEntry(foo, 0),
                                           Maps.immutableEntry(bar, "bar"));
    }

    @Test
    void concurrentAttributes() {
        final AttributeKey<Integer> foo = AttributeKey.valueOf("foo");
        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        final Attributes immutableAttributes = Attributes.of(foo, 0, bar, "bar");
        final ConcurrentAttributes concurrentAttributes = immutableAttributes.toConcurrentAttributes();
        assertThat(concurrentAttributes.attr(foo)).isZero();
        assertThat(concurrentAttributes.attr(bar)).isEqualTo("bar");

        concurrentAttributes.set(foo, 1);
        assertThat(immutableAttributes.attr(foo)).isZero();
        assertThat(concurrentAttributes.attr(foo)).isOne();
        concurrentAttributes.remove(bar);
        assertThat(concurrentAttributes.attr(bar)).isNull();
        assertThat(immutableAttributes.attr(bar)).isEqualTo("bar");
    }

    @Test
    void concurrentAttributes_hiddenValue() {
        final AttributeKey<Integer> foo = AttributeKey.valueOf("foo");
        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        final Attributes parent = Attributes.of(foo, 0, bar, "bar");

        final Attributes child = Attributes.builder(parent)
                                           .set(foo, null)
                                           .set(bar, "new")
                                           .build();
        assertThat(child.attr(foo)).isNull();
        assertThat(child.attr(bar)).isEqualTo("new");

        final ConcurrentAttributes concurrentAttributes = child.toConcurrentAttributes();
        assertThat(concurrentAttributes.attr(foo)).isNull();
        assertThat(concurrentAttributes.attr(bar)).isEqualTo("new");
    }

    @Test
    void equalsAndHash() {
        final AttributeKey<Integer> foo = AttributeKey.valueOf("foo");
        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        final Attributes attributes0 = Attributes.of(foo, 0, bar, "bar");
        final Attributes attributes1 = Attributes.of(foo, 0, bar, "bar");
        final Attributes attributes2 = Attributes.of(foo, 1, bar, "bar");
        final Attributes attributes3 = Attributes.builder(Attributes.of(foo, 0))
                                                 .set(bar, "bar")
                                                 .build();

        assertThat(attributes0).isEqualTo(attributes1);
        assertThat(attributes0.hashCode()).isEqualTo(attributes1.hashCode());

        assertThat(attributes0).isNotEqualTo(attributes2);
        assertThat(attributes0.hashCode()).isNotEqualTo(attributes2.hashCode());

        assertThat(attributes0).isEqualTo(attributes3);
        assertThat(attributes0.hashCode()).isEqualTo(attributes3.hashCode());
    }
}
