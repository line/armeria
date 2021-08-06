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

import static com.linecorp.armeria.internal.common.zookeeper.ZooKeeperPathUtil.validatePath;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.zookeeper.LegacyNodeValueCodec;

final class LegacyZooKeeperRegistrationSpec implements ZooKeeperRegistrationSpec {

    private static final byte[] EMPTY_BYTE = new byte[0];

    @Nullable
    private final Endpoint endpoint;
    private final String path;

    LegacyZooKeeperRegistrationSpec() {
        this(null);
    }

    LegacyZooKeeperRegistrationSpec(@Nullable Endpoint endpoint) {
        this.endpoint = endpoint;
        if (endpoint != null) {
            validatePath(endpoint.host(), "endpoint.host()");
            path = '/' + endpoint.host() + '_' + endpoint.port();
        } else {
            path = "/";
        }
    }

    @Nullable
    Endpoint endpoint() {
        return endpoint;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public boolean isSequential() {
        return false;
    }

    @Override
    public byte[] encodedInstance() {
        if (endpoint == null) {
            return EMPTY_BYTE;
        }
        return LegacyNodeValueCodec.INSTANCE.encode(endpoint);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("endpoint", endpoint)
                          .add("path", path)
                          .add("isSequential", isSequential())
                          .toString();
    }
}
