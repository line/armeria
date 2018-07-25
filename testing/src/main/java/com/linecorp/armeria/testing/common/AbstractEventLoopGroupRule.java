/*
 *  Copyright 2018 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.testing.common;

import javax.annotation.Nullable;

import org.junit.rules.ExternalResource;
import org.junit.rules.TestRule;

import com.linecorp.armeria.common.util.EventLoopGroups;

import io.netty.channel.EventLoopGroup;

abstract class AbstractEventLoopGroupRule extends ExternalResource {

    private final int numThreads;
    private final String threadNamePrefix;
    private final boolean useDaemonThreads;

    @Nullable
    private volatile EventLoopGroup group;

    AbstractEventLoopGroupRule(int numThreads, String threadNamePrefix, boolean useDaemonThreads) {
        this.numThreads = numThreads;
        this.threadNamePrefix = threadNamePrefix;
        this.useDaemonThreads = useDaemonThreads;
    }

    EventLoopGroup group() {
        final EventLoopGroup group = this.group;
        if (group == null) {
            throw new IllegalStateException(EventLoopGroup.class.getSimpleName() + " not initialized");
        }
        return group;
    }

    @Override
    protected void before() throws Throwable {
        group = EventLoopGroups.newEventLoopGroup(numThreads, threadNamePrefix, useDaemonThreads);
    }

    /**
     * Shuts down all threads created by this {@link TestRule} asynchronously.
     * Call {@code rule.get().shutdownGracefully().sync()} if you want to wait for complete termination.
     */
    @Override
    protected void after() {
        final EventLoopGroup group = this.group;
        if (group != null) {
            this.group = null;
            group.shutdownGracefully();
        }
    }
}
