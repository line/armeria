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
package com.linecorp.armeria.client.routing;

import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DecoratingRemoteInvoker;
import com.linecorp.armeria.client.RemoteInvoker;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;

import java.lang.reflect.Method;
import java.net.URI;

public final class EndpointGroupInvoker extends DecoratingRemoteInvoker {
    private String groupName;

    /**
     * Creates a new instance that decorates the specified {@link RemoteInvoker},
     * such that invocations are made against one of the endpoints in end
     * {@link EndpointGroup} named {@code groupName}.
     */
    public EndpointGroupInvoker(RemoteInvoker delegate, String groupName) {
        super(delegate);
        this.groupName = groupName;
    }

    @Override
    public <T> Future<T> invoke(EventLoop eventLoop, URI uri,
                                ClientOptions options,
                                ClientCodec codec, Method method,
                                Object[] args) throws Exception {
        final Endpoint selectedEndpoint = EndpointGroupRegistry.selectNode(groupName);
        final String nodeAddress = selectedEndpoint.hostname() + ':' + selectedEndpoint.port();
        uri = URI.create(EndpointGroupUtil.replaceEndpointGroup(uri.toString(), nodeAddress));

        return delegate().invoke(eventLoop, uri, options, codec, method, args);
    }
}
