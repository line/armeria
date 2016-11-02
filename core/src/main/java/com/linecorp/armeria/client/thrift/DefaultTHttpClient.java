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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UserClient;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftCall;
import com.linecorp.armeria.common.thrift.ThriftReply;

final class DefaultTHttpClient extends UserClient<ThriftCall, ThriftReply> implements THttpClient {

    private final AtomicInteger nextSeqId = new AtomicInteger();

    DefaultTHttpClient(ClientBuilderParams params,
                       Client<ThriftCall, ThriftReply> delegate,
                       SessionProtocol sessionProtocol, Endpoint endpoint) {
        super(params, delegate, sessionProtocol, endpoint);
    }

    @Override
    public ThriftReply execute(String path, Class<?> serviceType, String method, Object... args) {
        return executeMultiplexed(path, serviceType, "", method, args);
    }

    @Override
    public ThriftReply executeMultiplexed(
            String path, Class<?> serviceType, String serviceName, String method, Object... args) {
        requireNonNull(serviceName, "serviceName");
        final ThriftCall call = new ThriftCall(nextSeqId.getAndIncrement(), serviceType, method, args);
        return execute(call.method(), path, serviceName, call, cause -> new ThriftReply(call.seqId(), cause));
    }
}
