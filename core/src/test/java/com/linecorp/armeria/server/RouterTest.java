/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.assertj.core.util.Lists;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

public class RouterTest {
    private static final Logger logger = LoggerFactory.getLogger(RouterTest.class);

    private static final BiConsumer<PathMapping, PathMapping> REJECT = (a, b) -> {
        throw new IllegalStateException("duplicate path mapping: " + a + "vs. " + b);
    };

    @Test
    public void testRouters() {
        final List<PathMapping> mappings = Lists.newArrayList(
                PathMapping.of("exact:/a"),         // router 1
                PathMapping.of("/b/{var}"),
                PathMapping.of("prefix:/c"),
                PathMapping.of("regex:/d([^/]+)"),  // router 2
                PathMapping.of("glob:/e/**/z"),
                PathMapping.of("exact:/f"),         // router 3
                PathMapping.of("/g/{var}"),
                PathMapping.of("glob:/h/**/z"),     // router 4
                PathMapping.of("prefix:/i")         // router 5
        );
        final List<Router<PathMapping>> routers = Routers.routers(mappings, Function.identity(), REJECT);
        assertThat(routers.size()).isEqualTo(5);

        // Map of a path string and a router index
        final List<Entry<String, Integer>> args = Lists.newArrayList(
                Maps.immutableEntry("/a", 0),
                Maps.immutableEntry("/b/1", 0),
                Maps.immutableEntry("/c/1", 0),
                Maps.immutableEntry("/dxxx/", 1),
                Maps.immutableEntry("/e/1/2/3/z", 1),
                Maps.immutableEntry("/f", 2),
                Maps.immutableEntry("/g/1", 2),
                Maps.immutableEntry("/h/1/2/3/z", 3),
                Maps.immutableEntry("/i/1/2/3", 4)
        );

        final PathMappingContext mappingCtx = mock(PathMappingContext.class);
        args.forEach(entry -> {
            logger.debug("Entry: path {} router {}", entry.getKey(), entry.getValue());
            for (int i = 0; i < 5; i++) {
                when(mappingCtx.path()).thenReturn(entry.getKey());
                final PathMapped<PathMapping> result = routers.get(i).find(mappingCtx);
                assertThat(result.isPresent()).isEqualTo(i == entry.getValue());
            }
        });
    }

    @Test
    public void duplicateMappings() {
        // Simple cases
        testDuplicateMappings(PathMapping.of("exact:/a"), PathMapping.of("exact:/a"));
        testDuplicateMappings(PathMapping.of("exact:/a"), PathMapping.of("/a"));
        testDuplicateMappings(PathMapping.of("prefix:/"), PathMapping.ofCatchAll());
    }

    /**
     * Should detect the duplicates even if the mappings are split into more than one router.
     */
    @Test
    public void duplicateMappingsWithRegex() {
        // Ensure that 3 routers are created first really.
        assertThat(Routers.routers(ImmutableList.of(PathMapping.of("/foo/:bar"),
                                                    PathMapping.ofRegex("not-trie-compatible"),
                                                    PathMapping.of("/bar/:baz")),
                                   Function.identity(), REJECT)).hasSize(3);

        testDuplicateMappings(PathMapping.of("/foo/:bar"),
                              PathMapping.ofRegex("not-trie-compatible"),
                              PathMapping.of("/foo/:qux"));
    }

    @Test
    public void duplicateMappingsWithHeaders() {
        // Not a duplicate if complexity is different.
        testNonDuplicateMappings(PathMapping.of("/foo"),
                                 new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                           ImmutableSet.of(HttpMethod.GET),
                                                           ImmutableList.of(), ImmutableList.of()));

        // Duplicate if supported methods overlap.
        testNonDuplicateMappings(new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                           ImmutableSet.of(HttpMethod.GET),
                                                           ImmutableList.of(), ImmutableList.of()),
                                 new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                           ImmutableSet.of(HttpMethod.POST),
                                                           ImmutableList.of(), ImmutableList.of()));
        testDuplicateMappings(new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                        ImmutableSet.of(HttpMethod.GET),
                                                        ImmutableList.of(), ImmutableList.of()),
                              new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                        ImmutableSet.of(HttpMethod.GET, HttpMethod.POST),
                                                        ImmutableList.of(), ImmutableList.of()));

        // Duplicate if consume types overlap.
        testNonDuplicateMappings(new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                           ImmutableSet.of(HttpMethod.POST),
                                                           ImmutableList.of(MediaType.PLAIN_TEXT_UTF_8),
                                                           ImmutableList.of()),
                                 new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                           ImmutableSet.of(HttpMethod.POST),
                                                           ImmutableList.of(MediaType.JSON_UTF_8),
                                                           ImmutableList.of()));
        testDuplicateMappings(new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                        ImmutableSet.of(HttpMethod.POST),
                                                        ImmutableList.of(MediaType.PLAIN_TEXT_UTF_8,
                                                                         MediaType.JSON_UTF_8),
                                                        ImmutableList.of()),
                              new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                        ImmutableSet.of(HttpMethod.POST),
                                                        ImmutableList.of(MediaType.JSON_UTF_8),
                                                        ImmutableList.of()));

        // Duplicate if produce types overlap.
        testNonDuplicateMappings(new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                           ImmutableSet.of(HttpMethod.POST),
                                                           ImmutableList.of(),
                                                           ImmutableList.of(MediaType.PLAIN_TEXT_UTF_8)),
                                 new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                           ImmutableSet.of(HttpMethod.POST),
                                                           ImmutableList.of(),
                                                           ImmutableList.of(MediaType.JSON_UTF_8)));
        testDuplicateMappings(new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                        ImmutableSet.of(HttpMethod.POST),
                                                        ImmutableList.of(),
                                                        ImmutableList.of(MediaType.PLAIN_TEXT_UTF_8,
                                                                         MediaType.JSON_UTF_8)),
                              new HttpHeaderPathMapping(PathMapping.of("/foo"),
                                                        ImmutableSet.of(HttpMethod.POST),
                                                        ImmutableList.of(),
                                                        ImmutableList.of(MediaType.PLAIN_TEXT_UTF_8)));
    }

    private static void testDuplicateMappings(PathMapping... mappings) {
        assertThatThrownBy(() -> Routers.routers(ImmutableList.copyOf(mappings), Function.identity(), REJECT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("duplicate path mapping:");
    }

    private static void testNonDuplicateMappings(PathMapping... mappings) {
        Routers.routers(ImmutableList.copyOf(mappings), Function.identity(), REJECT);
    }
}
