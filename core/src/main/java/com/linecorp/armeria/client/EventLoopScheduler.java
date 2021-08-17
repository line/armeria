/*
 * Copyright 2019 LINE Corporation
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
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.ReleasableHolder;

import io.netty.channel.EventLoop;

/**
 * A scheduler which is responsible for assigning an {@link EventLoop} to handle a connection to the
 * specified {@link Endpoint}.
 */
@FunctionalInterface
public interface EventLoopScheduler {

    /**
     * Acquires an {@link EventLoop} that is expected to handle a connection to the specified {@link Endpoint}.
     * The caller must release the returned {@link EventLoop} back by calling {@link ReleasableHolder#release()}
     * so that {@link ClientFactory} utilizes {@link EventLoop}s efficiently.
     *
     * @param sessionProtocol the {@link SessionProtocol} of the connection
     * @param endpointGroup the {@link EndpointGroup} where {@code endpoint} belongs to.
     * @param endpoint the {@link Endpoint} where a request is being sent.
     *                 {@code null} if the {@link Endpoint} is not known yet.
     */
    ReleasableHolder<EventLoop> acquire(SessionProtocol sessionProtocol,
                                        EndpointGroup endpointGroup,
                                        @Nullable Endpoint endpoint);
}
