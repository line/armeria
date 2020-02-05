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
package com.linecorp.armeria.testing.junit4.common;

import org.junit.rules.ExternalResource;
import org.junit.rules.TestRule;

import com.linecorp.armeria.internal.testing.EventLoopGroupRuleDelegate;

import io.netty.channel.EventLoopGroup;

abstract class AbstractEventLoopGroupRule extends ExternalResource {
    private final EventLoopGroupRuleDelegate delegate;

    AbstractEventLoopGroupRule(int numThreads, String threadNamePrefix, boolean useDaemonThreads) {
        delegate = new EventLoopGroupRuleDelegate(numThreads, threadNamePrefix, useDaemonThreads);
    }

    EventLoopGroup group() {
        return delegate.group();
    }

    @Override
    protected void before() throws Throwable {
        delegate.before();
    }

    /**
     * Shuts down all threads created by this {@link TestRule} asynchronously.
     * Call {@code rule.get().shutdownGracefully().sync()} if you want to wait for complete termination.
     */
    @Override
    protected void after() {
        delegate.after();
    }
}
