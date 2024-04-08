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

import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

/**
 * Handles callbacks for {@link SotwXdsStream}.
 * Note that it is important that exceptions are not raised from the callback.
 * Otherwise, the infinite loop will break as a discovery request is not sent.
 */
interface XdsResponseHandler {

    <I extends Message, O extends XdsResource> void handleResponse(
            ResourceParser<I, O> resourceParser, DiscoveryResponse value, SotwXdsStream sender);
}
