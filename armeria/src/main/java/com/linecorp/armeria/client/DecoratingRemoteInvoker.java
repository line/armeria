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

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.net.URI;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;

/**
 * A {@link RemoteInvoker} that decorates another {@link RemoteInvoker}.
 *
 * @see ClientOption#DECORATOR
 */
public abstract class DecoratingRemoteInvoker implements RemoteInvoker {

    private final RemoteInvoker delegate;

    /**
     * Creates a new instance that decorates the specified {@link RemoteInvoker}.
     */
    protected DecoratingRemoteInvoker(RemoteInvoker delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the {@link RemoteInvoker} being decorated.
     */
    @SuppressWarnings("unchecked")
    protected final <T extends RemoteInvoker> T delegate() {
        return (T) delegate;
    }

    @Override
    public <T> Future<T> invoke(EventLoop eventLoop, URI uri,
                                ClientOptions options,
                                ClientCodec codec, Method method,
                                Object[] args) throws Exception {

        return delegate().invoke(eventLoop, uri, options, codec, method, args);
    }

    @Override
    public void close() {
        delegate().close();
    }

    @Override
    public String toString() {
        final String simpleName = getClass().getSimpleName();
        final String name = simpleName.isEmpty() ? getClass().getName() : simpleName;
        return name + '(' + delegate() + ')';
    }
}
