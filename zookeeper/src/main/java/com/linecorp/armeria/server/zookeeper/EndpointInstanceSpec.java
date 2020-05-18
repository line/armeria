/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server.zookeeper;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.common.zookeeper.DefaultNodeValueCodec;

final class EndpointInstanceSpec implements InstanceSpec {

    private final Endpoint endpoint;
    private final String pathForRegistration;

    EndpointInstanceSpec(Endpoint endpoint) {
        this.endpoint = requireNonNull(endpoint, "endpoint");
        pathForRegistration = '/' + endpoint.host() + '_' + endpoint.port();
    }

    Endpoint endpoint() {
        return endpoint;
    }

    @Override
    public String pathForRegistration() {
        return pathForRegistration;
    }

    @Override
    public byte[] encodedInstance() {
        return DefaultNodeValueCodec.INSTANCE.encode(endpoint);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("endpoint", endpoint)
                          .add("pathForRegistration", pathForRegistration)
                          .toString();
    }
}
