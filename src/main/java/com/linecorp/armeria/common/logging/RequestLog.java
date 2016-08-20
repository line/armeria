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

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpRequest;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * Information collected while processing a {@link Request}.
 *
 * @see RequestLogBuilder
 */
public interface RequestLog extends MessageLog {
    /**
     * The {@link AttributeKey} of the {@link HttpHeaders} of the processed {@link HttpRequest}.
     */
    AttributeKey<HttpHeaders> HTTP_HEADERS = AttributeKey.valueOf(RequestLog.class, "HTTP_HEADERS");

    /**
     * The {@link AttributeKey} of the processed {@link RpcRequest}.
     */
    AttributeKey<RpcRequest> RPC_REQUEST = AttributeKey.valueOf(RequestLog.class, "RPC_REQUEST");

    /**
     * Returns the Netty {@link Channel} which handled the {@link Request}.
     */
    Channel channel();

    /**
     * Returns the {@link Scheme} of the {@link Request}.
     */
    Scheme scheme();

    /**
     * Returns the host name of the {@link Request}.
     */
    String host();

    /**
     * Returns the method of the {@link Request}.
     */
    String method();

    /**
     * Returns the path of the {@link Request}.
     */
    String path();
}
