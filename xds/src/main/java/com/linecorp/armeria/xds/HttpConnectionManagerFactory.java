/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

final class HttpConnectionManagerFactory implements XdsExtensionFactory {

    static final HttpConnectionManagerFactory INSTANCE = new HttpConnectionManagerFactory();
    private static final String NAME = "envoy.http_connection_manager";
    private static final String TYPE_URL =
            "type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3" +
            ".HttpConnectionManager";

    private HttpConnectionManagerFactory() {}

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> typeUrls() {
        return ImmutableList.of(TYPE_URL);
    }

    HttpConnectionManager create(Any config, XdsResourceValidator validator) {
        return validator.unpack(config, HttpConnectionManager.class);
    }
}
