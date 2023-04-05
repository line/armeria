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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.RouteCache.CachingRoutingContext;

class CachingRoutingContextTest {

    @Test
    void disableMatchingQueryParamsByCachingRoutingContext() {
        final Route route = Route.builder()
                                 .exact("/test")
                                 .methods(HttpMethod.GET)
                                 .matchesParams("foo=bar")
                                 .build();

        final RoutingContext context = mock(RoutingContext.class);
        when(context.path()).thenReturn("/test");
        when(context.method()).thenReturn(HttpMethod.GET);
        when(context.params()).thenReturn(QueryParams.of("foo", "qux"));
        when(context.requiresMatchingParamsPredicates()).thenReturn(true);

        assertThat(route.apply(context, false).isPresent()).isFalse(); // Because of the query parameters.

        final CachingRoutingContext cachingContext = new CachingRoutingContext(context);
        assertThat(route.apply(cachingContext, false).isPresent()).isTrue();
    }

    @Test
    void disableMatchingHeadersByCachingRoutingContext() {
        final Route route = Route.builder()
                                 .exact("/test")
                                 .methods(HttpMethod.GET)
                                 .matchesHeaders("foo=bar")
                                 .build();

        final RoutingContext context = mock(RoutingContext.class);
        when(context.path()).thenReturn("/test");
        when(context.method()).thenReturn(HttpMethod.GET);
        when(context.headers()).thenReturn(RequestHeaders.of(HttpMethod.GET, "/test", "foo", "qux"));
        when(context.requiresMatchingHeadersPredicates()).thenReturn(true);

        assertThat(route.apply(context, false).isPresent()).isFalse(); // Because of HTTP headers.

        final CachingRoutingContext cachingContext = new CachingRoutingContext(context);
        assertThat(route.apply(cachingContext, false).isPresent()).isTrue();
    }
}
