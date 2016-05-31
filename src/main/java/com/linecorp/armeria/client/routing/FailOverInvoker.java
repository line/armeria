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
import io.netty.channel.ChannelException;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

import java.lang.reflect.Method;
import java.net.SocketException;
import java.net.URI;

class FailOverInvoker extends DecoratingRemoteInvoker {
    final private int tryCount;

    /**
     * Creates a new instance that decorates the specified {@link RemoteInvoker}.
     *
     * @param delegate
     */
    FailOverInvoker(RemoteInvoker delegate, int tryCount) {
        super(delegate);
        this.tryCount = tryCount;
    }

    @Override
    public <T> Future<T> invoke(EventLoop eventLoop, URI uri,
                                ClientOptions options,
                                ClientCodec codec, Method method,
                                Object[] args) throws Exception {
        Promise<T> promise = eventLoop.newPromise();
        this.invoke(eventLoop, uri, options, codec, method, args, tryCount, promise);
        return promise;
    }


    private <T> void invoke(EventLoop eventLoop, URI uri,
                            ClientOptions options,
                            ClientCodec codec, Method method,
                            Object[] args, int tryCount, Promise<T> promise) throws Exception {
        delegate().invoke(eventLoop, uri, options, codec, method, args).addListener(future -> {
            if (future.isSuccess()) {
                promise.setSuccess((T) future.getNow());
            } else {
                if (isNetworkException(future.cause()) && tryCount > 0) {
                    invoke(eventLoop, uri, options, codec, method, args, tryCount - 1, promise);
                } else {
                    promise.setFailure(future.cause());
                }

            }
        });

    }

    /**
     * Check if the {@code exception} is a network exception
     */
    private boolean isNetworkException(Throwable exception) {
        return exception instanceof SocketException ||
                exception instanceof ChannelException;
    }

}
