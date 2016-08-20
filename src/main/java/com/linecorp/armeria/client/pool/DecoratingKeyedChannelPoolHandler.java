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

package com.linecorp.armeria.client.pool;

import static java.util.Objects.requireNonNull;

import io.netty.channel.Channel;

/**
 * A {@link KeyedChannelPoolHandler} that decorates an existing {@link KeyedChannelPoolHandler}.
 *
 * @param <K> the key type
 */
public class DecoratingKeyedChannelPoolHandler<K> implements KeyedChannelPoolHandler<K> {

    private final KeyedChannelPoolHandler<K> delegate;

    /**
     * Creates a new decorator with the specified {@code delegate}.
     */
    protected DecoratingKeyedChannelPoolHandler(KeyedChannelPoolHandler<K> delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    /**
     * Returns the {@link KeyedChannelPoolHandler} this handler decorates.
     */
    @SuppressWarnings("unchecked")
    protected <T extends KeyedChannelPoolHandler<K>> T delegate() {
        return (T) delegate;
    }

    @Override
    public void channelReleased(K key, Channel ch) throws Exception {
        delegate().channelReleased(key, ch);
    }

    @Override
    public void channelAcquired(K key, Channel ch) throws Exception {
        delegate().channelAcquired(key, ch);
    }

    @Override
    public void channelCreated(K key, Channel ch) throws Exception {
        delegate().channelCreated(key, ch);
    }

    @Override
    public void channelClosed(K key, Channel ch) throws Exception {
        delegate().channelClosed(key, ch);
    }
}
