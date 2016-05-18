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

package com.linecorp.armeria.common.logging;

import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.http.HttpHeaders;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

public interface RequestLog extends MessageLog {
    AttributeKey<HttpHeaders> HTTP_HEADERS = AttributeKey.valueOf(RequestLog.class, "HTTP_HEADERS");
    AttributeKey<RpcRequest> RPC_REQUEST = AttributeKey.valueOf(RequestLog.class, "RPC_REQUEST");

    Channel channel();
    Scheme scheme();
    String host();
    String method();
    String path();
}
