/*
 * Copyright 2015 LINE Corporation
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

import java.lang.reflect.Method;
import java.net.URI;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;

/**
 * Performs a remote invocation to a {@link URI}.
 */
public interface RemoteInvoker extends AutoCloseable {

    /**
     * Performs a remote invocation to the specified {@link URI}.
     *
     * @param eventLoop the {@link EventLoop} to perform the invocation
     * @param uri the {@link URI} of the server endpoint
     * @param options the {@link ClientOptions}
     * @param codec the {@link ClientCodec}
     * @param method the original {@link Method} that triggered the remote invocation
     * @param args the arguments of the remote invocation
     *
     * @return the {@link Future} that notifies the result of the remote invocation.
     */
    <T> Future<T> invoke(EventLoop eventLoop, URI uri, ClientOptions options, ClientCodec codec,
                         Method method, Object[] args) throws Exception;

    /**
     * Closes the underlying socket connection and releases its associated resources.
     */
    @Override
    void close();
}
