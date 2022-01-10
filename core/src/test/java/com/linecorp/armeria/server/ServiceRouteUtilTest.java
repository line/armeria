/*
 * Copyright 2022 LINE Corporation
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

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;

import io.netty.channel.Channel;

class ServiceRouteUtilTest {
    private final ServiceRequestContext fakeCtx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    private final ServerConfig config = fakeCtx.config().server().config();
    private final Channel channel = fakeCtx.log().partial().channel();

    @Test
    void optionRequest() {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.OPTIONS, "*")
                                                     .authority("foo.com")
                                                     .build();
        final RoutingContext routingContext = ServiceRouteUtil.newRoutingContext(config, channel, headers);
        assertThat(routingContext.status()).isEqualTo(RoutingStatus.OPTIONS);
    }

    @Test
    void normalRequest() {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.GET, "/")
                                                     .authority("foo.com")
                                                     .build();
        final RoutingContext routingContext = ServiceRouteUtil.newRoutingContext(config, channel, headers);
        assertThat(routingContext.status()).isEqualTo(RoutingStatus.OK);
    }

    @Test
    void invalidPath() {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.GET, "abc/def")
                                                     .authority("foo.com")
                                                     .build();
        final RoutingContext routingContext = ServiceRouteUtil.newRoutingContext(config, channel, headers);
        assertThat(routingContext.status()).isEqualTo(RoutingStatus.INVALID_PATH);
    }
}
