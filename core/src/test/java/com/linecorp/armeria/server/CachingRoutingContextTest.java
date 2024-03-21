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

import static com.linecorp.armeria.server.RoutingContextTest.virtualHost;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.RouteCache.CachingRoutingContext;

class CachingRoutingContextTest {

    @Test
    void disableMatchingQueryParamsByCachingRoutingContext() {
        final VirtualHost virtualHost = virtualHost();
        final Route route = Route.builder()
                                 .exact("/test")
                                 .methods(HttpMethod.GET)
                                 .matchesParams("foo=bar")
                                 .build();

        final RequestTarget reqTarget = RequestTarget.forServer("/test?foo=qux");
        assertThat(reqTarget).isNotNull();

        final RoutingContext context =
                new RoutingContextWrapper(DefaultRoutingContext.of(
                        virtualHost, virtualHost.defaultHostname(),
                        reqTarget,
                        RequestHeaders.of(HttpMethod.GET, reqTarget.pathAndQuery()),
                        RoutingStatus.OK, SessionProtocol.H2C)) {
                    @Override
                    public boolean requiresMatchingParamsPredicates() {
                        return true;
                    }
                };

        assertThat(context.params()).isEqualTo(QueryParams.of("foo", "qux"));
        assertThat(route.apply(context, false).isPresent()).isFalse(); // Because of the query parameters.

        final CachingRoutingContext cachingContext = new CachingRoutingContext(context);
        assertThat(route.apply(cachingContext, false).isPresent()).isTrue();
    }

    @Test
    void disableMatchingHeadersByCachingRoutingContext() {
        final VirtualHost virtualHost = virtualHost();
        final Route route = Route.builder()
                                 .exact("/test")
                                 .methods(HttpMethod.GET)
                                 .matchesHeaders("foo=bar")
                                 .build();

        final RequestTarget reqTarget = RequestTarget.forServer("/test");
        assertThat(reqTarget).isNotNull();

        final RoutingContext context =
                new RoutingContextWrapper(DefaultRoutingContext.of(
                        virtualHost, virtualHost.defaultHostname(),
                        reqTarget,
                        RequestHeaders.of(HttpMethod.GET, reqTarget.pathAndQuery(),
                                          "foo", "qux"),
                        RoutingStatus.OK, SessionProtocol.H2C)) {
                    @Override
                    public boolean requiresMatchingHeadersPredicates() {
                        return true;
                    }
                };

        assertThat(context.headers().contains("foo", "qux")).isTrue();
        assertThat(route.apply(context, false).isPresent()).isFalse(); // Because of HTTP headers.

        final CachingRoutingContext cachingContext = new CachingRoutingContext(context);
        assertThat(route.apply(cachingContext, false).isPresent()).isTrue();
    }
}
