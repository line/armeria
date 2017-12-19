/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.pool;

import io.netty.channel.Channel;

/**
 * A skeletal {@link KeyedChannelPoolHandler} implementation to minimize the effort to implement this interface.
 * Extend this class to implement only few of the provided handler methods.
 *
 * @param <K> the key type
 */
public class KeyedChannelPoolHandlerAdapter<K> implements KeyedChannelPoolHandler<K> {

    static final KeyedChannelPoolHandlerAdapter<Object> NOOP = new KeyedChannelPoolHandlerAdapter<Object>() {};

    /**
     * Creates a new instance.
     */
    protected KeyedChannelPoolHandlerAdapter() {}

    @Override
    public void channelReleased(K key, Channel ch) throws Exception {}

    @Override
    public void channelAcquired(K key, Channel ch) throws Exception {}

    @Override
    public void channelCreated(K key, Channel ch) throws Exception {}

    @Override
    public void channelClosed(K key, Channel ch) throws Exception {}
}
