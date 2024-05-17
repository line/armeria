/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.thrift;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Iterator;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import testing.thrift.main.FooService.AsyncIface;

/**
 * Test for {@link ThriftCallServiceBuilder}.
 */
class ThriftCallServiceBuilderTest {
    @Test
    void nullAndEmptyCases() {
        assertThrows(NullPointerException.class, () ->
                ThriftCallService.builder().addService(null)
        );
        assertThrows(NullPointerException.class, () ->
                ThriftCallService.builder().addService("", (Object) null)
        );
        assertThrows(NullPointerException.class, () ->
                ThriftCallService.builder().addService("", null, null)
        );
        assertThrows(NullPointerException.class, () ->
                ThriftCallService.builder().addService("", null, null, null)
        );
        assertThrows(NullPointerException.class, () ->
                ThriftCallService.builder().addService("", (Iterable<?>) null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                ThriftCallService.builder().addService("", new ArrayList<>())
        );
    }

    @Test
    void testBuilder() {
        final AsyncIface defaultServiceImpl = mock(AsyncIface.class);
        final AsyncIface fooServiceImpl = mock(AsyncIface.class);
        final AsyncIface barServiceImpl = mock(AsyncIface.class);
        final ThriftCallService service = ThriftCallService
                .builder()
                .addService(defaultServiceImpl)
                .addService("foo", fooServiceImpl)
                .addService("foobar", fooServiceImpl)
                .addService("foobar", barServiceImpl)
                .addService("foobarOnce", fooServiceImpl, barServiceImpl)
                .addService("foobarList", ImmutableList.of(fooServiceImpl, barServiceImpl))
                .addServices(ImmutableMap.of("fooMap", fooServiceImpl, "barMap", barServiceImpl))
                .addServices(ImmutableMap.of("fooIterableMap",
                                             ImmutableList.of(fooServiceImpl, barServiceImpl)))
                .build();
        assertTrue(service.entries().containsKey(""));
        final Iterator<?> defaultIterator = service.entries().get("").implementations.iterator();
        assertEquals(defaultServiceImpl, defaultIterator.next());
        assertFalse(defaultIterator.hasNext());

        assertTrue(service.entries().containsKey("foo"));
        final Iterator<?> fooIterator = service.entries().get("foo").implementations.iterator();
        assertEquals(fooServiceImpl, fooIterator.next());
        assertFalse(fooIterator.hasNext());

        assertTrue(service.entries().containsKey("foobar"));
        final Iterator<?> foobarIterator = service.entries().get("foobar").implementations.iterator();
        assertEquals(fooServiceImpl, foobarIterator.next());
        assertEquals(barServiceImpl, foobarIterator.next());
        assertFalse(foobarIterator.hasNext());

        assertTrue(service.entries().containsKey("foobarOnce"));
        final Iterator<?> foobarOnceIterator = service.entries().get("foobarOnce").implementations.iterator();
        assertEquals(fooServiceImpl, foobarOnceIterator.next());
        assertEquals(barServiceImpl, foobarOnceIterator.next());
        assertFalse(foobarOnceIterator.hasNext());

        assertTrue(service.entries().containsKey("foobarList"));
        final Iterator<?> foobarListIterator = service.entries().get("foobarList").implementations.iterator();
        assertEquals(fooServiceImpl, foobarListIterator.next());
        assertEquals(barServiceImpl, foobarListIterator.next());
        assertFalse(foobarListIterator.hasNext());

        assertTrue(service.entries().containsKey("fooMap"));
        final Iterator<?> fooMapIterator = service.entries().get("fooMap").implementations.iterator();
        assertEquals(fooServiceImpl, fooMapIterator.next());
        assertFalse(fooMapIterator.hasNext());

        assertTrue(service.entries().containsKey("barMap"));
        final Iterator<?> barMapIterator = service.entries().get("barMap").implementations.iterator();
        assertEquals(barServiceImpl, barMapIterator.next());
        assertFalse(barMapIterator.hasNext());

        assertTrue(service.entries().containsKey("fooIterableMap"));
        final Iterator<?> fooIterableMapIterator =
                service.entries().get("fooIterableMap").implementations.iterator();
        assertEquals(fooServiceImpl, fooIterableMapIterator.next());
        assertEquals(barServiceImpl, fooIterableMapIterator.next());
        assertFalse(fooIterableMapIterator.hasNext());
    }
}
