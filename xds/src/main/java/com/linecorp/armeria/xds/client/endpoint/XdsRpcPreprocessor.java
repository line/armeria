/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.xds.XdsBootstrap;

/**
 * A {@link RpcPreprocessor} implementation which allows clients to execute requests based on
 * xDS (* Discovery Service). A typical user may make requests like the following:
 * <pre>{@code
 * XdsBootstrap bootstrap = XdsBootstrap.of(...);
 * XdsRpcPreprocessor rpcPreprocessor = XdsRpcPreprocessor.of("my-listener" ,bootstrap);
 * HelloService.Iface iface = ThriftClients.newClient(rpcPreprocessor, HelloService.Iface.class);
 * iface.hello() // the request will be routed based on how the listener "my-listener" is configured
 * rpcPreprocessor.close();
 * }</pre>
 * Once a {@link XdsRpcPreprocessor} is no longer used, invoking {@link XdsRpcPreprocessor#close()}
 * may help save resources.
 */
public final class XdsRpcPreprocessor extends XdsPreprocessor<RpcRequest, RpcResponse>
        implements RpcPreprocessor {

    /**
     * Creates a {@link XdsRpcPreprocessor}.
     */
    public static XdsRpcPreprocessor of(String listenerName, XdsBootstrap xdsBootstrap) {
        return new XdsRpcPreprocessor(listenerName, xdsBootstrap);
    }

    private XdsRpcPreprocessor(String listenerName, XdsBootstrap xdsBootstrap) {
        super(listenerName, xdsBootstrap, RpcResponse::of,
              (xdsFilter, preClient) -> xdsFilter.rpcDecorate(preClient::execute));
    }
}
