/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.hessian;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Unwrappable;

/**
 * A generic Hessian client.
 *
 * <p>You will usually create a Hessian client object that implements a specific interface
 * (e.g. {@code HelloService}):
 * <pre>{@code
 * HelloService client = Clients.newClient(
 *         "hessian+http://127.0.0.1/service/helloService.hs", HelloService.class);
 * client.hello("John Doe", ...);
 * }</pre>
 * However, if you want a generic Hesssian client, this client may be useful:
 * <pre>{@code
 * HessianClient client = Clients.newClient("hessian+http://127.0.0.1/", HessianClient.class);
 * client.execute("/hello", HelloService.class, "hello", "John Doe");
 * }</pre>
 */
@UnstableApi
public interface HessianClient extends Unwrappable, ClientBuilderParams {

    /**
     * Executes the specified Hessian call.
     * @param path the path of the Hessian service
     * @param serviceType the Hessian service interface
     * @param method the method name
     * @param args the arguments of the call
     */
    RpcResponse execute(String path, Class<?> serviceType, String method, Object... args);

    @Override
    RpcClient unwrap();
}
