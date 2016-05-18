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

package com.linecorp.armeria.client;

import java.time.Duration;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.http.HttpHeaders;

import io.netty.util.AttributeKey;

/**
 * Provides information about an invocation and related utilities. Every client request has its own
 * {@link ClientRequestContext} instance.
 */
public interface ClientRequestContext extends RequestContext {

    AttributeKey<HttpHeaders> HTTP_HEADERS = AttributeKey.valueOf(ClientRequestContext.class, "HTTP_HEADERS");

    Endpoint endpoint();
    ClientOptions options();

    long writeTimeoutMillis();
    void setWriteTimeoutMillis(long writeTimeoutMillis);
    void setWriteTimeout(Duration writeTimeout);

    long responseTimeoutMillis();
    void setResponseTimeoutMillis(long responseTimeoutMillis);
    void setResponseTimeout(Duration responseTimeout);

    long maxResponseLength();
    void setMaxResponseLength(long maxResponseLength);
}
