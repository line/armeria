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
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpResponse;

import io.netty.util.AttributeKey;

/**
 * Information collected while processing a {@link Response}.
 *
 * @see ResponseLogBuilder
 */
public interface ResponseLog extends MessageLog {
    /**
     * The {@link AttributeKey} of the {@link HttpHeaders} of the processed {@link HttpResponse}.
     */
    AttributeKey<HttpHeaders> HTTP_HEADERS = AttributeKey.valueOf(RequestLog.class, "HTTP_HEADERS");

    /**
     * The {@link AttributeKey} of the processed {@link RpcResponse}.
     */
    AttributeKey<RpcResponse> RPC_RESPONSE = AttributeKey.valueOf(RequestLog.class, "RPC_RESPONSE");

    /**
     * Returns the {@link RequestLog} of the corresponding {@link Request}.
     */
    RequestLog request();

    /**
     * Returns the status code specific to the current {@link SessionProtocol}.
     */
    int statusCode();
}
