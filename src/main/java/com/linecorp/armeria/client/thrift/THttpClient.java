/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.client.thrift;

import com.linecorp.armeria.client.ClientOptionDerivable;
import com.linecorp.armeria.common.thrift.ThriftReply;

/**
 * A generic Thrift-over-HTTP client.
 *
 * <p>You will usually create a Thrift client object that implements a specific Thrift service interface
 * (e.g. {@code HelloService.AsyncIface}):
 * <pre>{@code
 * HelloService.AsyncIface client = Clients.newClient(
 *         "tbinary+http://127.0.0.1/hello", HelloService.AsyncIface.class);
 * client.hello("John Doe", ...);
 * }</pre>
 * However, if you want a generic Thrift client that works with any Thrift services, this client may be useful:
 * <pre>{@code
 * ThriftClient client = Clients.newClient("tbinary+http://127.0.0.1/", ThriftClient.class);
 * client.execute("/hello", HelloService.Iface.class, "hello", "John Doe");
 * client.execute("/foo", FooService.Iface.class, "foo", "arg1", "arg2", ...);
 * }</pre>
 */
public interface THttpClient extends ClientOptionDerivable<THttpClient> {
    /**
     * Executes the specified Thrift call.
     *
     * @param path the path of the Thrift service
     * @param serviceType the Thrift service interface
     * @param method the method name
     * @param args the arguments of the call
     */
    ThriftReply execute(String path, Class<?> serviceType, String method, Object... args);
}
