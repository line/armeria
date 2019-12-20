/*
 * Copyright 2016 LINE Corporation
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
/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Lists;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

class DefaultAttributeMapTest {

    // Forked from Netty 4.1.34 at 2993760e9261f046db88a0e8ccf9edf4e9b0acad

    private DefaultAttributeMap map;

    @BeforeEach
    void setup() {
        map = new DefaultAttributeMap(null);
    }

    @Test
    void testMapExists() {
        assertNotNull(map);
    }

    @Test
    void testGetSetString() {
        final AttributeKey<String> key = AttributeKey.valueOf("Nothing");
        final Attribute<String> one = map.attr(key);

        assertSame(one, map.attr(key));

        one.setIfAbsent("Whoohoo");
        assertSame("Whoohoo", one.get());

        one.setIfAbsent("What");
        assertNotSame("What", one.get());

        one.remove();
        assertNull(one.get());
    }

    @Test
    void testGetSetInt() {
        final AttributeKey<Integer> key = AttributeKey.valueOf("Nada");
        final Attribute<Integer> one = map.attr(key);

        assertSame(one, map.attr(key));

        one.setIfAbsent(3653);
        assertEquals(Integer.valueOf(3653), one.get());

        one.setIfAbsent(1);
        assertNotSame(1, one.get());

        one.remove();
        assertNull(one.get());
    }

    // See https://github.com/netty/netty/issues/2523
    @Test
    void testSetRemove() {
        final AttributeKey<Integer> key = AttributeKey.valueOf("key");

        final Attribute<Integer> attr = map.attr(key);
        attr.set(1);
        assertSame(1, attr.getAndRemove());

        final Attribute<Integer> attr2 = map.attr(key);
        attr2.set(2);
        assertSame(2, attr2.get());
        assertNotSame(attr, attr2);
    }

    @Test
    void testGetAndSetWithNull() {
        final AttributeKey<Integer> key = AttributeKey.valueOf("key");

        final Attribute<Integer> attr = map.attr(key);
        attr.set(1);
        assertSame(1, attr.getAndSet(null));

        final Attribute<Integer> attr2 = map.attr(key);
        attr2.set(2);
        assertSame(2, attr2.get());
        assertSame(attr, attr2);
    }

    @Test
    void testIteratorWithEmptyMap() {
        assertThat(map.attrs().hasNext()).isFalse();
    }

    @Test
    void testIteratorWithSparseMap() {
        final AttributeKey<Integer> key = AttributeKey.valueOf(DefaultAttributeMap.class, "KEY");
        map.attr(key).set(42);

        final List<Attribute<?>> attrs = Lists.newArrayList(map.attrs());
        assertEquals(Collections.singletonList(map.attr(key)), attrs);

        map.attr(key).remove();
        assertFalse(map.attrs().hasNext());
    }

    @Test
    void testIteratorWithFullMap() {
        final List<AttributeKey<Integer>> expectedKeys = new ArrayList<>();
        for (int i = 0; i < 1024; i++) {
            final AttributeKey<Integer> key =
                    AttributeKey.valueOf(DefaultAttributeMapTest.class, String.valueOf(i));
            expectedKeys.add(key);
            map.attr(key).set(i);
        }

        // Make sure all buckets are filled.
        for (int i = 0; i < map.attributes.length(); i++) {
            assertNotNull(map.attributes.get(i));
        }

        // Make sure the Iterator yields all attributes.
        assertEquals(expectedKeys, actualKeys());

        // Make sure the Iterator does not yield the attributes whose 'removed' property is 'true'.
        for (int i = 0; i < map.attributes.length(); i++) {
            final Attribute<?> a = map.attributes.get(i);
            a.remove();

            // A head attribute is never removed from the linked list.
            assertSame(a, map.attributes.get(i));

            // Remove the removed key from the list of expected expectedKeys.
            expectedKeys.remove(a.key());
        }

        assertEquals(expectedKeys, actualKeys());
    }

    private List<AttributeKey<?>> actualKeys() {
        return Lists.newArrayList(map.attrs()).stream().sorted((a, b) -> {
            final Integer aVal = a.key().id();
            final Integer bVal = b.key().id();
            return aVal.compareTo(bVal);
        }).map(Attribute::key).collect(Collectors.toList());
    }

    @Test
    void hasAttributeInRoot() {
        final DefaultAttributeMap root = new DefaultAttributeMap(null);
        final DefaultAttributeMap child = new DefaultAttributeMap(root);

        final AttributeKey<String> foo = AttributeKey.valueOf("foo");
        final Attribute<String> fooInRoot = root.attr(foo);
        fooInRoot.set("foo1");

        assertThat(root.hasAttr(foo)).isTrue();
        assertThat(child.hasAttr(foo)).isTrue();
        assertThat(child.hasOwnAttr(foo)).isFalse();

        final Attribute<String> fooInChild = child.attr(foo);
        assertThat(fooInRoot.get()).isEqualTo(fooInChild.get());

        fooInChild.set("foo2");
        assertThat(child.hasOwnAttr(foo)).isTrue(); // Now it's true.
        assertThat(fooInChild.get()).isEqualTo("foo2");
        assertThat(fooInRoot.get()).isNotEqualTo(fooInChild.get());

        assertThat(fooInChild.getAndSet("foo3")).isEqualTo("foo2");
        assertThat(fooInChild.get()).isEqualTo("foo3");

        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        final Attribute<String> barInRoot = root.attr(bar);
        barInRoot.set("bar");

        final Attribute<String> barInChild = child.attr(bar);
        assertThat(child.hasOwnAttr(bar)).isFalse();
        assertThat(barInChild.getAndSet("bar1")).isEqualTo("bar");
        assertThat(child.hasOwnAttr(bar)).isTrue();
        assertThat(barInChild.get()).isEqualTo("bar1");
        assertThat(barInRoot.get()).isEqualTo("bar");
        assertThat(barInChild.getAndSet("bar2")).isEqualTo("bar1");

        final AttributeKey<String> baz = AttributeKey.valueOf("baz");
        final Attribute<String> bazInRoot = root.attr(baz);
        bazInRoot.set("baz");

        final Attribute<String> bazInChild = child.attr(baz);

        assertThat(child.hasOwnAttr(baz)).isFalse();
        assertThat(bazInChild.setIfAbsent("baz1")).isNull();
        assertThat(child.hasOwnAttr(baz)).isTrue();

        assertThat(bazInChild.get()).isEqualTo("baz1");
        assertThat(bazInRoot.get()).isEqualTo("baz");

        final AttributeKey<String> qux = AttributeKey.valueOf("qux");
        final Attribute<String> quxInRoot = root.attr(qux);
        quxInRoot.set("qux");

        final Attribute<String> quxInChild = child.attr(qux);

        assertThat(child.hasOwnAttr(qux)).isFalse();
        assertThat(quxInChild.compareAndSet("oops", "qux1")).isFalse();
        assertThat(child.hasOwnAttr(qux)).isFalse();
        assertThat(quxInChild.compareAndSet("qux", "qux1")).isTrue();
        assertThat(child.hasOwnAttr(qux)).isTrue();
        assertThat(quxInChild.get()).isEqualTo("qux1");
        assertThat(quxInRoot.get()).isEqualTo("qux");
    }

    @Test
    void hasNoAttributeInRoot() {
        final DefaultAttributeMap root = new DefaultAttributeMap(null);
        final DefaultAttributeMap child = new DefaultAttributeMap(root);

        final AttributeKey<String> foo = AttributeKey.valueOf("foo");
        final Attribute<String> fooInChild = child.attr(foo);
        fooInChild.set("foo1");

        assertThat(root.hasAttr(foo)).isFalse();
        assertThat(child.hasAttr(foo)).isTrue();
        assertThat(child.hasOwnAttr(foo)).isTrue();

        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        final Attribute<String> barInChild = child.attr(bar);
        assertThat(barInChild.getAndSet("bar")).isNull();
        assertThat(barInChild.get()).isEqualTo("bar");
        assertThat(root.hasAttr(bar)).isFalse();

        final AttributeKey<String> baz = AttributeKey.valueOf("baz");
        final Attribute<String> bazInChild = child.attr(baz);
        assertThat(bazInChild.setIfAbsent("baz")).isNull();
        assertThat(child.hasOwnAttr(baz)).isTrue();
        assertThat(root.hasAttr(baz)).isFalse();

        final AttributeKey<String> qux = AttributeKey.valueOf("qux");
        final Attribute<String> quxInChild = child.attr(qux);
        quxInChild.set("qux");

        assertThat(quxInChild.compareAndSet("qux", "qux1")).isTrue();
        assertThat(child.hasOwnAttr(qux)).isTrue();
        assertThat(quxInChild.get()).isEqualTo("qux1");
        assertThat(root.hasAttr(qux)).isFalse();
    }

    @Test
    void attrsWithRoot() {
        final DefaultAttributeMap root = new DefaultAttributeMap(null);
        final DefaultAttributeMap child = new DefaultAttributeMap(root);

        final AttributeKey<String> foo = AttributeKey.valueOf("foo");
        root.attr(foo).set("foo");

        Iterator<Attribute<?>> childIt = child.attrs();
        @SuppressWarnings("unchecked")
        final Attribute<String> next = (Attribute<String>) childIt.next();
        assertThat(next.get()).isEqualTo("foo");
        assertThat(childIt.hasNext()).isFalse();
        assertThatThrownBy(childIt::next).isInstanceOf(NoSuchElementException.class);

        next.set("foo1");
        assertThat(child.hasOwnAttr(foo)).isTrue();
        assertThat(child.attr(foo).get()).isEqualTo("foo1");
        // root context attribute remains unaffected.
        assertThat(root.attr(foo).get()).isEqualTo("foo");

        // Set the same attribute to child.
        child.ownAttr(foo).set("foo2");
        childIt = child.attrs();
        assertThat(childIt.next().get()).isEqualTo("foo2");
        assertThat(childIt.hasNext()).isFalse();

        // Set a new attribute to child.
        final AttributeKey<String> bar = AttributeKey.valueOf("bar");
        child.attr(bar).set("bar");

        final Iterator<Attribute<?>> childOwnIt = child.ownAttrs();
        final List<String> attributeValues = new ArrayList<>(2);
        attributeValues.add((String) childOwnIt.next().get());
        attributeValues.add((String) childOwnIt.next().get());
        assertThat(childOwnIt.hasNext()).isFalse();
        assertThat(attributeValues).containsExactlyInAnyOrder("foo2", "bar");

        // Set a new attribute to root.
        final AttributeKey<String> baz = AttributeKey.valueOf("baz");
        root.attr(baz).set("baz");

        childIt = child.attrs();
        attributeValues.clear();
        attributeValues.add((String) childIt.next().get());
        attributeValues.add((String) childIt.next().get());
        assertThat(attributeValues).containsExactlyInAnyOrder("foo2", "bar");

        @SuppressWarnings("unchecked")
        final Attribute<String> shouldBeBaz = (Attribute<String>) childIt.next();
        assertThat(shouldBeBaz.get()).isEqualTo("baz");
        assertThat(childIt.hasNext()).isFalse();

        shouldBeBaz.set("baz2");
        assertThat(root.attr(baz).get()).isEqualTo("baz");
        assertThat(child.attr(baz).get()).isEqualTo("baz2");
    }
}
