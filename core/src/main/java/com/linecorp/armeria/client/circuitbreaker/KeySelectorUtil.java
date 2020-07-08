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
package com.linecorp.armeria.client.circuitbreaker;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.RpcRequest;

final class KeySelectorUtil {

    /**
     * A {@link KeySelector} that returns remote method name as a key.
     */
    static final KeySelector<String> methodSelector = (ctx, req) -> {
        final RpcRequest rpcReq = ctx.rpcRequest();
        return rpcReq != null ? rpcReq.method() : ctx.method().name();
    };

    /**
     * A {@link KeySelector} that returns a key consisted of remote host name,
     * IP address and port number.
     */
    static final KeySelector<String> hostSelector =
            (ctx, req) -> {
                final Endpoint endpoint = ctx.endpoint();
                if (endpoint == null) {
                    return "UNKNOWN";
                } else {
                    final String ipAddr = endpoint.ipAddr();
                    if (ipAddr == null || endpoint.isIpAddrOnly()) {
                        return endpoint.authority();
                    } else {
                        return endpoint.authority() + '/' + ipAddr;
                    }
                }
            };

    /**
     * A {@link KeySelector} that returns a key consisted of remote host name,
     * IP address, port number and method name.
     */
    static final KeySelector<String> hostAndMethodSelector =
            (ctx, req) -> hostSelector.get(ctx, req) + '#' + methodSelector.get(ctx, req);

    private KeySelectorUtil() {}
}
