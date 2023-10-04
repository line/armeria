/*
 * Copyright 2023 LINE Corporation
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

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;

/**
 * A simple {@link EventLoop} implementation which executes tasks immediately
 * from the caller thread. Note that {@link #invokeAny(Collection)},
 * {@link #invokeAll(Collection, long, TimeUnit)} and other variants have been
 * omitted for simplicity.
 */
public final class ImmediateEventLoop extends DefaultEventLoop {

    public static final EventLoop INSTANCE = new ImmediateEventLoop();

    private ImmediateEventLoop() {}

    @Override
    public void execute(Runnable command) {
        command.run();
    }

    @Override
    public boolean inEventLoop() {
        return true;
    }
}
