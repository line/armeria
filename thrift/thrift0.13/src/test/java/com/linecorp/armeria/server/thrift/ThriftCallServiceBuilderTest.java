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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
                ThriftCallService.builder().addService("", null)
        );
        assertThrows(NullPointerException.class, () ->
                ThriftCallService.builder().addServices("", (Object) null)
        );
        assertThrows(NullPointerException.class, () ->
                ThriftCallService.builder().addServices("", null, null)
        );
        assertThrows(NullPointerException.class, () ->
                ThriftCallService.builder().addServices("", null, null, null)
        );
        assertThrows(NullPointerException.class, () ->
                ThriftCallService.builder().addServices("", (Iterable<?>) null)
        );
        assertThrows(IllegalArgumentException.class, () ->
                ThriftCallService.builder().addServices("", new ArrayList<>())
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
                .addServices("foobarOnce", fooServiceImpl, barServiceImpl)
                .addServices("foobarList", ImmutableList.of(fooServiceImpl, barServiceImpl))
                .addServices(ImmutableMap.of("fooMap", fooServiceImpl, "barMap", barServiceImpl))
                .addServices(ImmutableMap.of("fooIterableMap",
                                             ImmutableList.of(fooServiceImpl, barServiceImpl)))
                .build();
        final Map<String, List<Object>> actualEntries =
                service.entries().entrySet().stream()
                       .collect(ImmutableMap.toImmutableMap(
                               Map.Entry::getKey,
                               e -> ImmutableList.copyOf(e.getValue().implementations)));

        final Map<String, List<Object>> expectedEntries = ImmutableMap.of(
                "", ImmutableList.of(defaultServiceImpl),
                "foo", ImmutableList.of(fooServiceImpl),
                "foobar", ImmutableList.of(fooServiceImpl, barServiceImpl),
                "foobarOnce", ImmutableList.of(fooServiceImpl, barServiceImpl),
                "foobarList", ImmutableList.of(fooServiceImpl, barServiceImpl),
                "fooMap", ImmutableList.of(fooServiceImpl),
                "barMap", ImmutableList.of(barServiceImpl),
                "fooIterableMap", ImmutableList.of(fooServiceImpl, barServiceImpl));

        assertThat(actualEntries).isEqualTo(expectedEntries);
    }
}
