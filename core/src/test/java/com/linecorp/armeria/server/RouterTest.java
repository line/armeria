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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map.Entry;
import java.util.function.Function;

import org.assertj.core.util.Lists;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;

public class RouterTest {
    private static final Logger logger = LoggerFactory.getLogger(RouterTest.class);

    @Test
    public void testRouters() {
        List<PathMapping> mappings = Lists.newArrayList(
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
        List<Router<PathMapping>> routers = Routers.routers(mappings, Function.identity());
        assertThat(routers.size()).isEqualTo(5);

        // Map of a path string and a router index
        List<Entry<String, Integer>> args = Lists.newArrayList(
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
}
