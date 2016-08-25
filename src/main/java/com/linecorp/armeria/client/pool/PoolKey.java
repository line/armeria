/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.pool;

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;

import com.linecorp.armeria.common.SessionProtocol;

/**
 * The default key of {@link KeyedChannelPool}. It consists of:
 * <ul>
 *   <li>the server's {@link InetSocketAddress}</li>
 *   <li>the server's {@link SessionProtocol}</li>
 * </ul>
 */
public final class PoolKey {

    private final InetSocketAddress remoteAddress;
    private final SessionProtocol sessionProtocol;
    private final String value;

    /**
     * Creates a new key with the specified {@code remoteAddress} and {@code sessionProtocol}.
     */
    public PoolKey(InetSocketAddress remoteAddress, SessionProtocol sessionProtocol) {
        this.remoteAddress = requireNonNull(remoteAddress, "remoteAddress");
        this.sessionProtocol = requireNonNull(sessionProtocol, "sessionProtocol");
        value = sessionProtocol.uriText() + "://" + remoteAddress.getHostString() + ':' +
                remoteAddress.getPort();
    }

    /**
     * Returns the remote address of the server associated with this key.
     */
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    /**
     * Returns the {@link SessionProtocol} of the server associated with this key.
     */
    public SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof PoolKey)) {
            return false;
        }

        PoolKey poolKey = (PoolKey) o;
        return value.equals(poolKey.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return "PoolKey[" + value + ']';
    }
}
