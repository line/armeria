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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Lists;

import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

public class DefaultAttributeMapTest {

    private DefaultAttributeMap map;

    @Before
    public void setup() {
        map = new DefaultAttributeMap();
    }

    @Test
    public void testMapExists() {
        assertNotNull(map);
    }

    @Test
    public void testGetSetString() {
        AttributeKey<String> key = AttributeKey.valueOf("Nothing");
        Attribute<String> one = map.attr(key);

        assertSame(one, map.attr(key));

        one.setIfAbsent("Whoohoo");
        assertSame("Whoohoo", one.get());

        one.setIfAbsent("What");
        assertNotSame("What", one.get());

        one.remove();
        assertNull(one.get());
    }

    @Test
    public void testGetSetInt() {
        AttributeKey<Integer> key = AttributeKey.valueOf("Nada");
        Attribute<Integer> one = map.attr(key);

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
    public void testSetRemove() {
        AttributeKey<Integer> key = AttributeKey.valueOf("key");

        Attribute<Integer> attr = map.attr(key);
        attr.set(1);
        assertSame(1, attr.getAndRemove());

        Attribute<Integer> attr2 = map.attr(key);
        attr2.set(2);
        assertSame(2, attr2.get());
        assertNotSame(attr, attr2);
    }

    @Test
    public void testGetAndSetWithNull() {
        AttributeKey<Integer> key = AttributeKey.valueOf("key");

        Attribute<Integer> attr = map.attr(key);
        attr.set(1);
        assertSame(1, attr.getAndSet(null));

        Attribute<Integer> attr2 = map.attr(key);
        attr2.set(2);
        assertSame(2, attr2.get());
        assertSame(attr, attr2);
    }

    @Test
    public void testIteratorWithEmptyMap() {
        assertThat(map.attrs().hasNext(), is(false));
    }

    @Test
    public void testIteratorWithSparseMap() {
        final AttributeKey<Integer> key = AttributeKey.valueOf(DefaultAttributeMap.class, "KEY");
        map.attr(key).set(42);

        final List<Attribute<?>> attrs = Lists.newArrayList(map.attrs());
        assertEquals(Collections.singletonList(map.attr(key)), attrs);

        map.attr(key).remove();
        assertFalse(map.attrs().hasNext());
    }

    @Test
    public void testIteratorWithFullMap() {
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
            Attribute<?> a = map.attributes.get(i);
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
            Integer aVal = a.key().id();
            Integer bVal = b.key().id();
            return aVal.compareTo(bVal);
        }).map(Attribute::key).collect(Collectors.toList());
    }
}
