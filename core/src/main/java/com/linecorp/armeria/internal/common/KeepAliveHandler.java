/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import io.netty.channel.ChannelHandlerContext;

/**
 * A keep-alive handler for a connection.
 */
public interface KeepAliveHandler {

    /**
     * Initializes this {@link KeepAliveHandler} with the {@link ChannelHandlerContext}.
     */
    void initialize(ChannelHandlerContext ctx);

    /**
     * Destroys scheduled resources.
     */
    void destroy();

    /**
     * Returns whether this {@link KeepAliveHandler} manages an HTTP/2 connection.
     */
    boolean isHttp2();

    /**
     * Invoked when a read or write is performed.
     */
    void onReadOrWrite();

    /**
     * Invoked when a PING read or write is performed.
     */
    void onPing();

    /**
     * Invoked when a <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-6.7">PING ACK</a> is received.
     * Note that this method is only valid for an HTTP/2 connection.
     */
    void onPingAck(long data);

    /**
     * Returns whether this {@link KeepAliveHandler} is closing or closed.
     */
    boolean isClosing();

    /**
     * Returns whether a connection managed by this {@link KeepAliveHandler} reaches its lifespan.
     */
    boolean needToCloseConnection();

    /**
     * Increases the number of requests received or sent.
     */
    void increaseNumRequests();
}
