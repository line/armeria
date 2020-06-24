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

package com.linecorp.armeria.testing.junit.common;

import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.linecorp.armeria.internal.testing.EventLoopGroupRuleDelegate;

import io.netty.channel.EventLoopGroup;

abstract class AbstractEventLoopGroupExtension extends AbstractAllOrEachExtension {
    private final EventLoopGroupRuleDelegate delegate;

    AbstractEventLoopGroupExtension(int numThreads, String threadNamePrefix, boolean useDaemonThreads) {
        delegate = new EventLoopGroupRuleDelegate(numThreads, threadNamePrefix, useDaemonThreads);
    }

    EventLoopGroup group() {
        return delegate.group();
    }

    @Override
    public void before(ExtensionContext context) throws Exception {
        try {
            delegate.before();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set up before callback", t);
        }
    }

    /**
     * Shuts down all threads created by this {@link Extension} asynchronously.
     * Call {@code rule.get().shutdownGracefully().sync()} if you want to wait for complete termination.
     */
    @Override
    public void after(ExtensionContext context) throws Exception {
        delegate.after();
    }
}
