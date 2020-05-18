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
package com.linecorp.armeria.client.zookeeper;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.internal.common.zookeeper.DefaultNodeValueCodec;

enum DefaultDiscoverySpec implements DiscoverySpec {
    INSTANCE;

    @Nullable
    @Override
    public String pathForDiscovery() {
        return null;
    }

    @Override
    public Endpoint decode(byte[] zNodeValue) {
        return DefaultNodeValueCodec.INSTANCE.decode(zNodeValue);
    }
}
