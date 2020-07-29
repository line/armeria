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

package com.linecorp.armeria.client.proxy;

import java.net.InetSocketAddress;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ProxiedAddresses;
import com.linecorp.armeria.server.ServiceRequestContext;

final class HAProxyConfigSelector implements ProxyConfigSelector {

    private HAProxyConfigSelector() {
    }

    static final HAProxyConfigSelector INSTANCE = new HAProxyConfigSelector();

    @Override
    public ProxyConfig select(SessionProtocol protocol, Endpoint endpoint) {
        // use proxy information in context if available
        final ClientRequestContext clientCtx = ClientRequestContext.currentOrNull();
        if (clientCtx != null && clientCtx.root() != null) {
            final ServiceRequestContext serviceCtx = clientCtx.root();
            assert serviceCtx != null;
            final ProxiedAddresses proxiedAddresses = serviceCtx.proxiedAddresses();
            if (!proxiedAddresses.destinationAddresses().isEmpty()) {
                return new HAProxyConfig(proxiedAddresses.sourceAddress(),
                                         proxiedAddresses.destinationAddresses().get(0));
            }
        }

        // otherwise use the endpoint information
        assert endpoint.ipAddr() != null;
        return new HAProxyConfig(new InetSocketAddress(endpoint.ipAddr(), endpoint.port()));
    }
}
