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

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isCorsPreflightRequest;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.util.ChannelUtil;

import io.netty.channel.Channel;

final class ServiceRouteUtil {

    static RoutingContext newRoutingContext(ServerConfig serverConfig, Channel channel,
                                            SessionProtocol sessionProtocol,
                                            RequestHeaders headers, RequestTarget reqTarget) {

        final String hostname = hostname(headers);
        final int port = ChannelUtil.getPort(channel.localAddress(), 0);
        final String originalPath = headers.path();

        final RoutingStatus routingStatus;
        if (headers.method() == HttpMethod.OPTIONS) {
            if (isCorsPreflightRequest(headers)) {
                routingStatus = RoutingStatus.CORS_PREFLIGHT;
            } else if ("*".equals(originalPath)) {
                routingStatus = RoutingStatus.OPTIONS;
            } else {
                routingStatus = RoutingStatus.OK;
            }
        } else {
            routingStatus = RoutingStatus.OK;
        }

        return DefaultRoutingContext.of(serverConfig.findVirtualHost(hostname, port),
                                        hostname, reqTarget, headers, routingStatus, sessionProtocol);
    }

    private static String hostname(RequestHeaders headers) {
        final String authority = headers.authority();
        assert authority != null;
        final int hostnameColonIdx = authority.lastIndexOf(':');
        if (hostnameColonIdx < 0) {
            return authority;
        }

        return authority.substring(0, hostnameColonIdx);
    }

    private ServiceRouteUtil() {}
}
