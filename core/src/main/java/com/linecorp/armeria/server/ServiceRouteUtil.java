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

import java.net.InetSocketAddress;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.common.PathAndQuery;

import io.netty.channel.Channel;

final class ServiceRouteUtil {

    static RoutingContext newRoutingContext(ServerConfig serverConfig, Channel channel,
                                            RequestHeaders headers) {

        final String hostname = hostname(headers);
        final int port = ((InetSocketAddress) channel.localAddress()).getPort();
        final String originalPath = headers.path();

        PathAndQuery pathAndQuery = null;
        final RoutingStatus routingStatus;
        if (originalPath.isEmpty() || originalPath.charAt(0) != '/') {
            // 'OPTIONS * HTTP/1.1' will be handled by HttpServerHandler
            if (headers.method() == HttpMethod.OPTIONS && "*".equals(originalPath)) {
                routingStatus = RoutingStatus.OPTIONS;
            } else {
                routingStatus = RoutingStatus.INVALID_PATH;
            }
        } else {
            if (isCorsPreflightRequest(headers)) {
                routingStatus = RoutingStatus.CORS_PREFLIGHT;
            } else {
                pathAndQuery = PathAndQuery.parse(originalPath);
                if (pathAndQuery != null) {
                    routingStatus = RoutingStatus.OK;
                } else {
                    routingStatus = RoutingStatus.INVALID_PATH;
                }
            }
        }

        final VirtualHost virtualHost = serverConfig.findVirtualHost(hostname, port);
        if (routingStatus != RoutingStatus.OK) {
            return DefaultRoutingContext.of(virtualHost, hostname, headers.path(), /* query */ null, headers,
                                            routingStatus);
        } else {
            return DefaultRoutingContext.of(virtualHost, hostname, pathAndQuery, headers, routingStatus);
        }
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
