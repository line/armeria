/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.xds;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.protobuf.Message;

import io.envoyproxy.envoy.config.route.v3.Route.ActionCase;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteConfigurationOrBuilder;

final class RouteResourceParser extends ResourceParser {

    public static final RouteResourceParser INSTANCE = new RouteResourceParser();

    private RouteResourceParser() {}

    @Override
    RouteResourceHolder parse(Message message) {
        if (!(message instanceof RouteConfiguration)) {
            throw new IllegalArgumentException("message not type of RouteConfiguration");
        }
        final RouteResourceHolder holder = new RouteResourceHolder((RouteConfiguration) message);
        holder.routes().forEach(route -> {
            checkArgument(route.getActionCase() == ActionCase.ROUTE,
                          "Only Route ActionCase is supported. Received %s.",
                          route.getActionCase());
            final RouteAction routeAction = route.getRoute();
            checkArgument(route.hasRoute(),
                          "Route doesn't have a RouteAction. Received %s.",
                          route.getRoute());
            checkArgument(routeAction.hasCluster(),
                          "RouteAction doesn't have a Cluster. Received %s.",
                          routeAction.getCluster());
        });
        return holder;
    }

    @Override
    String name(Message message) {
        if (!(message instanceof RouteConfiguration)) {
            throw new IllegalArgumentException("message not type of RouteConfiguration");
        }
        return ((RouteConfigurationOrBuilder) message).getName();
    }

    @Override
    Class<RouteConfiguration> clazz() {
        return RouteConfiguration.class;
    }

    @Override
    boolean isFullStateOfTheWorld() {
        return false;
    }

    @Override
    XdsType type() {
        return XdsType.ROUTE;
    }
}
