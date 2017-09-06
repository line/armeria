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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.Channel;

/**
 * Helper Handler delegate event to decorated  {@link KeyedChannelPoolHandler}.
 * Ignore Exception when created {@link KeyedChannelPoolHandler} throw Exception.s
 */
class SafeKeyedChannelPoolHandler<K> implements KeyedChannelPoolHandler<K> {
    private static final Logger logger = LoggerFactory.getLogger(SafeKeyedChannelPoolHandler.class);

    private final KeyedChannelPoolHandler<K> handler;

    SafeKeyedChannelPoolHandler(KeyedChannelPoolHandler<K> handler) {
        this.handler = handler;
    }

    private static void logFailure(String handlerName, Throwable cause) {
        logger.warn("Exception handling {}()", handlerName, cause);
    }

    @Override
    public void channelReleased(K key, Channel ch) {
        try {
            handler.channelReleased(key, ch);
        } catch (Exception e) {
            logFailure("channelReleased", e);
        }
    }

    @Override
    public void channelAcquired(K key, Channel ch) {
        try {
            handler.channelAcquired(key, ch);
        } catch (Exception e) {
            logFailure("channelAcquired", e);
        }
    }

    @Override
    public void channelCreated(K key, Channel ch) {
        try {
            handler.channelCreated(key, ch);
        } catch (Exception e) {
            logFailure("channelCreated", e);
        }
    }

    @Override
    public void channelClosed(K key, Channel ch) {
        try {
            handler.channelClosed(key, ch);
        } catch (Exception e) {
            logFailure("channelClosed", e);
        }
    }
}
