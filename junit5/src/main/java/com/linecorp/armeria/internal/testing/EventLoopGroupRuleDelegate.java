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

package com.linecorp.armeria.internal.testing;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventLoopGroups;

import io.netty.channel.EventLoopGroup;

/**
 * A delegate that has common testing methods of {@link EventLoopGroup}.
 */
public final class EventLoopGroupRuleDelegate {

    private final int numThreads;
    private final String threadNamePrefix;
    private final boolean useDaemonThreads;

    @Nullable
    private volatile EventLoopGroup group;

    public EventLoopGroupRuleDelegate(int numThreads, String threadNamePrefix, boolean useDaemonThreads) {
        this.numThreads = numThreads;
        this.threadNamePrefix = threadNamePrefix;
        this.useDaemonThreads = useDaemonThreads;
    }

    public EventLoopGroup group() {
        final EventLoopGroup group = this.group;
        if (group == null) {
            throw new IllegalStateException(EventLoopGroup.class.getSimpleName() + " not initialized");
        }
        return group;
    }

    public void before() throws Throwable {
        group = EventLoopGroups.newEventLoopGroup(numThreads, threadNamePrefix, useDaemonThreads);
    }

    public void after() {
        @Nullable
        final EventLoopGroup group = this.group;
        if (group != null) {
            this.group = null;
            group.shutdownGracefully();
        }
    }
}
