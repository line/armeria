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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UserClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;

import io.netty.channel.EventLoop;

final class DefaultTHttpClient extends UserClient<THttpClient, ThriftCall, ThriftReply> implements THttpClient {

    private final AtomicInteger nextSeqId = new AtomicInteger();

    DefaultTHttpClient(Client<ThriftCall, ThriftReply> delegate, Supplier<EventLoop> eventLoopSupplier,
                       SessionProtocol sessionProtocol, ClientOptions options, Endpoint endpoint) {

        super(delegate, eventLoopSupplier, sessionProtocol, options, endpoint);
    }

    @Override
    public ThriftReply execute(String path, Class<?> serviceType, String method, Object... args) {
        final ThriftCall call = new ThriftCall(nextSeqId.getAndIncrement(), serviceType, method, args);
        return execute(call.method(), path, call, cause -> new ThriftReply(call.seqId(), cause));
    }

    @Override
    protected THttpClient newInstance(
            Client<ThriftCall, ThriftReply> delegate, Supplier<EventLoop> eventLoopSupplier,
            SessionProtocol sessionProtocol, ClientOptions options, Endpoint endpoint) {

        return new DefaultTHttpClient(delegate, eventLoopSupplier, sessionProtocol, options, endpoint);
    }
}
