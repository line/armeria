/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.channel.EventLoop;

/**
 * A {@link ClientRequestContext} which allows certain properties to be mutable before
 * initialization is finalized.
 */
@UnstableApi
public interface PreClientRequestContext extends ClientRequestContext {

    /**
     * Sets the {@link EndpointGroup} used for the current {@link Request}.
     */
    void endpointGroup(EndpointGroup endpointGroup);

    /**
     * Sets the {@link SessionProtocol} of the current {@link Request}.
     */
    void sessionProtocol(SessionProtocol sessionProtocol);

    /**
     * Sets the {@link EventLoop} which will handle this request. Because changing
     * the assigned {@link EventLoop} can lead to unexpected behavior, this property
     * can be set only once. Because the assigned {@link EventLoop} can influence the number of
     * connections made to an {@link Endpoint}, it is recommended to understand {@link EventLoopScheduler}
     * before manually setting this value.
     *
     * @see EventLoopScheduler
     */
    void eventLoop(EventLoop eventLoop);
}
