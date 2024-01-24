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

import com.google.protobuf.Message;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

final class RouteResourceParser extends ResourceParser {

    static final RouteResourceParser INSTANCE = new RouteResourceParser();

    private RouteResourceParser() {}

    @Override
    String name(Message message) {
        if (!(message instanceof RouteConfiguration)) {
            throw new IllegalArgumentException("message not type of RouteConfiguration");
        }
        return ((RouteConfiguration) message).getName();
    }

    @Override
    Class<RouteConfiguration> clazz() {
        return RouteConfiguration.class;
    }

    @Override
    XdsType type() {
        return XdsType.ROUTE;
    }
}
