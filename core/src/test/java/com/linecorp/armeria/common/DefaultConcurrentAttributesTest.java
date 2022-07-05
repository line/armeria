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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import com.linecorp.armeria.internal.server.DefaultServiceRequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.util.AttributeKey;

class DefaultConcurrentAttributesTest {

    @Test
    void testGetSetString() {
        final ConcurrentAttributes map = ConcurrentAttributes.of();
        final AttributeKey<String> key = AttributeKey.valueOf("str");
        assertThat(map.attr(key)).isNull();

        assertThat(map.getAndSet(key, "Whoohoo")).isNull();
        assertThat(map.attr(key)).isEqualTo("Whoohoo");

        assertThat(map.getAndSet(key, "What")).isEqualTo("Whoohoo");
        assertThat(map.attr(key)).isEqualTo("What");

        assertThat(map.getAndSet(key, null)).isEqualTo("What");
        assertThat(map.attr(key)).isNull();

        assertThat(map.getAndSet(key, "Why")).isNull();
        assertThat(map.attr(key)).isEqualTo("Why");
    }

    @Test
    void testGetSetInt() {
        final ConcurrentAttributes map = ConcurrentAttributes.of();
        final AttributeKey<Integer> key = AttributeKey.valueOf("int");
        assertThat(map.attr(key)).isNull();

        assertThat(map.getAndSet(key, 3653)).isNull();
        assertThat(map.attr(key)).isEqualTo(3653);

        assertThat(map.getAndSet(key, 1)).isEqualTo(3653);
        assertThat(map.attr(key)).isEqualTo(1);

        assertThat(map.getAndSet(key, null)).isEqualTo(1);
        assertThat(map.attr(key)).isNull();

        assertThat(map.getAndSet(key, 2)).isNull();
        assertThat(map.attr(key)).isEqualTo(2);
    }

    @Test
    void testGetSetWithNull() {
        final ConcurrentAttributes map = ConcurrentAttributes.of();
        final AttributeKey<Integer> key = AttributeKey.valueOf("key");

        assertThat(map.getAndSet(key, 1)).isNull();
        assertThat(map.attr(key)).isEqualTo(1);

        assertThat(map.getAndSet(key, null)).isEqualTo(1);
        assertThat(map.attr(key)).isNull();
    }

    @Test
    void testIterator() {
        final ConcurrentAttributes map = ConcurrentAttributes.of();
        assertThat(map.attrs().hasNext()).isFalse();

        final AttributeKey<Integer> key = AttributeKey.valueOf(DefaultConcurrentAttributes.class, "KEY");
        assertThat(map.getAndSet(key, 42)).isNull();

        final ArrayList<Entry<AttributeKey<?>, Object>> attrs = Lists.newArrayList(map.attrs());
        assertThat(attrs).hasSize(1);
    }

    @Test
    void testIteratorWithFullMap() {
        final DefaultConcurrentAttributes map = (DefaultConcurrentAttributes) ConcurrentAttributes.of();
        final List<AttributeKey<Integer>> expectedKeys = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            final AttributeKey<Integer> key =
                    AttributeKey.valueOf(DefaultConcurrentAttributesTest.class, String.valueOf(i));
            expectedKeys.add(key);
            assertThat(map.getAndSet(key, i)).isNull();
        }

        // Make sure all buckets are filled.
        for (int i = 0; i < map.attributes.length(); i++) {
            assertThat(map.attributes.get(i)).isNotNull();
        }

        // Make sure the Iterator yields all attributes.
        assertThat(expectedKeys).isEqualTo(actualKeys(map));

        for (int i = 1023; i >= 0; i -= 5) {
            final AttributeKey<Integer> key = expectedKeys.get(i);
            assertThat(map.getAndSet(key, null)).isEqualTo(i);
            expectedKeys.remove(i);
        }

        assertThat(expectedKeys).isEqualTo(actualKeys(map));
    }

    private static List<AttributeKey<?>> actualKeys(DefaultConcurrentAttributes map) {
        return ImmutableList.copyOf(map.attrs()).stream().sorted((a, b) -> {
            final Integer aVal = a.getKey().id();
            final Integer bVal = b.getKey().id();
            return aVal.compareTo(bVal);
        }).map(Entry::getKey).collect(toImmutableList());
    }

    @Test
    void hasAttributeInRoot() {
        final DefaultServiceRequestContext root =
                (DefaultServiceRequestContext) ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final ConcurrentAttributes child = ConcurrentAttributes.fromParent(root.attributes());

        // root: [foo], child: []
        final AttributeKey<String> foo = AttributeKey.valueOf("foo");
        assertThat(root.setAttr(foo, "foo")).isNull();

        assertThat(root.attr(foo)).isEqualTo("foo");
        assertThat(child.attr(foo)).isEqualTo("foo");
        assertThat(child.ownAttr(foo)).isNull();

        // root: [foo], child: [foo2]
        assertThat(child.getAndSet(foo, "foo2")).isEqualTo("foo");
        assertThat(child.ownAttr(foo)).isEqualTo("foo2");
        assertThat(child.attr(foo)).isEqualTo("foo2");
        assertThat(root.attr(foo)).isEqualTo("foo");

        // root: [foo], child: [null]
        assertThat(child.getAndSet(foo, null)).isEqualTo("foo2");
        assertThat(child.ownAttr(foo)).isNull();
        assertThat(child.attr(foo)).isNull();
        assertThat(root.attr(foo)).isEqualTo("foo");

        // root: [foo], child: [foo3]
        assertThat(child.getAndSet(foo, "foo3")).isNull(); // Should not return "foo".
        assertThat(child.ownAttr(foo)).isEqualTo("foo3");
        assertThat(child.attr(foo)).isEqualTo("foo3");
        assertThat(root.attr(foo)).isEqualTo("foo");
    }

    @Test
    void hasNoAttributeInRoot() {
        final DefaultServiceRequestContext root =
                (DefaultServiceRequestContext) ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final ConcurrentAttributes child = ConcurrentAttributes.fromParent(root.attributes());

        final AttributeKey<String> foo = AttributeKey.valueOf("foo");
        // root: [], child: [foo]
        assertThat(child.getAndSet(foo, "foo")).isNull();
        assertThat(root.attr(foo)).isNull();
        assertThat(child.attr(foo)).isEqualTo("foo");
        assertThat(child.ownAttr(foo)).isEqualTo("foo");

        // root: [], child: [null]
        assertThat(child.getAndSet(foo, null)).isEqualTo("foo");
        assertThat(root.attr(foo)).isNull();
        assertThat(child.attr(foo)).isNull();
        assertThat(child.ownAttr(foo)).isNull();

        // root: [], child: [foo2]
        assertThat(child.getAndSet(foo, "foo2")).isNull();
        assertThat(root.attr(foo)).isNull();
        assertThat(child.attr(foo)).isEqualTo("foo2");
        assertThat(child.ownAttr(foo)).isEqualTo("foo2");
    }

    @Test
    void attrsWithRoot() {
        final DefaultServiceRequestContext root =
                (DefaultServiceRequestContext) ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final ConcurrentAttributes child = ConcurrentAttributes.fromParent(root.attributes());

        final AttributeKey<String> foo = AttributeKey.valueOf("foo");
        // root: [foo], child: []
        assertThat(root.setAttr(foo, "foo")).isNull();

        Iterator<Entry<AttributeKey<?>, Object>> childIt = child.attrs();
        final Entry<AttributeKey<?>, Object> next = childIt.next();
        assertThat(next.getValue()).isEqualTo("foo");
        assertThat(childIt.hasNext()).isFalse();
        assertThatThrownBy(childIt::next).isInstanceOf(NoSuchElementException.class);

        // root: [foo], child: [foo1]
        next.setValue("foo1");
        assertThat(child.attr(foo)).isEqualTo("foo1");
        assertThat(child.ownAttr(foo)).isEqualTo("foo1");
        // The value of entry is changed.
        assertThat(next.getValue()).isEqualTo("foo1");
        // root attribute remains unaffected.
        assertThat(root.attr(foo)).isEqualTo("foo");

        // Set a new attribute to child.
        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        // root: [foo], child: [foo1, bar]
        assertThat(child.getAndSet(bar, "bar")).isNull();

        childIt = child.attrs();
        final List<String> attributeValues = new ArrayList<>(2);
        Entry<AttributeKey<?>, Object> barEntry = null;
        for (int i = 0; i < 2; i++) {
            final Entry<AttributeKey<?>, Object> tempEntry = childIt.next();
            attributeValues.add((String) tempEntry.getValue());
            if ("bar".equals(tempEntry.getValue())) {
                barEntry = tempEntry;
            }
        }
        assertThat(childIt.hasNext()).isFalse();
        assertThat(attributeValues).containsExactlyInAnyOrder("foo1", "bar");

        assertThat(barEntry).isNotNull();
        // root: [foo], child: [foo1, bar1]
        barEntry.setValue("bar1");
        assertThat(child.attr(bar)).isEqualTo("bar1");

        // Set a new attribute to root.
        final AttributeKey<String> baz = AttributeKey.valueOf("baz");
        // root: [foo, baz], child: [foo1, bar1]
        assertThat(root.setAttr(baz, "baz")).isNull();

        childIt = child.attrs();
        attributeValues.clear();
        attributeValues.add((String) childIt.next().getValue());
        attributeValues.add((String) childIt.next().getValue());
        assertThat(attributeValues).containsExactlyInAnyOrder("foo1", "bar1");

        assertThat(childIt.next().getValue()).isEqualTo("baz");
        // childIt does not yield foo in root.
        assertThat(childIt.hasNext()).isFalse();

        // child own attrs()
        attributeValues.clear();
        final Iterator<Entry<AttributeKey<?>, Object>> childOwnIt = child.ownAttrs();
        for (int i = 0; i < 2; i++) {
            attributeValues.add((String) childOwnIt.next().getValue());
        }
        assertThat(childOwnIt.hasNext()).isFalse();
        assertThat(attributeValues).containsExactlyInAnyOrder("foo1", "bar1");
    }

    @Test
    void getAndSet() {
        final AttributeKey<Integer> foo = AttributeKey.valueOf("foo");
        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        final ConcurrentAttributes attributes = ConcurrentAttributes.of();
        attributes.set(foo, 0)
                  .set(bar, "bar");
        final Integer oldFoo = attributes.getAndSet(foo, 1);
        final String oldBar = attributes.getAndSet(bar, "new");
        assertThat(oldFoo).isZero();
        assertThat(oldBar).isEqualTo("bar");

        assertThat(attributes.attr(foo)).isEqualTo(1);
        assertThat(attributes.attr(bar)).isEqualTo("new");
    }

    @Test
    void equalsAndHash() {
        final AttributeKey<Integer> foo = AttributeKey.valueOf("foo");
        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        final ConcurrentAttributes attributes0 = Attributes.of(foo, 0, bar, "bar").toConcurrentAttributes();
        final ConcurrentAttributes attributes1 = Attributes.of(foo, 0, bar, "bar").toConcurrentAttributes();
        final ConcurrentAttributes attributes2 = Attributes.of(foo, 1, bar, "bar").toConcurrentAttributes();
        final ConcurrentAttributes attributes3 = Attributes.builder(Attributes.of(foo, 0))
                                                           .set(bar, "bar")
                                                           .build().toConcurrentAttributes();

        assertThat(attributes0).isEqualTo(attributes1);
        assertThat(attributes0.hashCode()).isEqualTo(attributes1.hashCode());

        assertThat(attributes0).isNotEqualTo(attributes2);
        assertThat(attributes0.hashCode()).isNotEqualTo(attributes2.hashCode());

        assertThat(attributes0).isEqualTo(attributes3);
        assertThat(attributes0.hashCode()).isEqualTo(attributes3.hashCode());
    }
}
