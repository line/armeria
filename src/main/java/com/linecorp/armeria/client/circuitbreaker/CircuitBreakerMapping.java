/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import java.lang.reflect.Method;
import java.net.URI;

import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.client.ClientOptions;

import io.netty.channel.EventLoop;

/**
 * Returns a {@link CircuitBreaker} instance from remote invocation parameters.
 */
@FunctionalInterface
public interface CircuitBreakerMapping {

    /**
     * Returns the {@link CircuitBreaker} mapped to the given parameters.
     */
    CircuitBreaker get(EventLoop eventLoop, URI uri, ClientOptions options, ClientCodec codec, Method method,
                       Object[] args) throws Exception;
}
