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

import io.netty.channel.Channel;

/**
 * Handles the events produced by {@link KeyedChannelPool}.
 *
 * @param <K> the key type
 */
public interface KeyedChannelPoolHandler<K> {

    /**
     * Invoked when the specified {@code channel} has been created for the specified {@code key}.
     */
    void channelCreated(K key, Channel ch) throws Exception;

    /**
     * Invoked when the specified {@code channel} has been acquired from the pool.
     */
    void channelAcquired(K key, Channel ch) throws Exception;

    /**
     * Invoked when the specified {@code channel} has been released to the pool.
     */
    void channelReleased(K key, Channel ch) throws Exception;

    /**
     * Invoked when the specified {@code channel} has been closed and removed from the pool.
     */
    void channelClosed(K key, Channel ch) throws Exception;
}
