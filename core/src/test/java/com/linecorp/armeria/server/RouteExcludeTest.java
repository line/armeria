/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.server.RoutingContextTest.virtualHost;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;

class RouteExcludeTest {
    @Test
    void excludePathPrefix() {
        Route route = Route.builder().pathPrefix("/foo").excludePathPrefix("/foo/bar").build();
        assertThat(route.apply(getRoutingContext("/foo/baz"), false).isPresent()).isTrue();
        // PrefixPathMapping will automatically add '/' to the end of the given path prefix.
        assertThat(route.apply(getRoutingContext("/foo/bar"), false).isPresent()).isTrue();
        assertThat(route.apply(getRoutingContext("/foo/bar/"), false).isPresent()).isFalse();
    }

    private RoutingContext getRoutingContext(String path) {
        return new DefaultRoutingContext(virtualHost(), "example.com",
                                         RequestHeaders.of(HttpMethod.GET, path),
                                         path, null, false);
    }
}
