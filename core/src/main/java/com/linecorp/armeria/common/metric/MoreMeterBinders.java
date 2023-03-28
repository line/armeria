/*
 *  Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common.metric;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.netty.channel.EventLoopGroup;

/**
 *  Provides useful {@link MeterBinder}s to monitor various Armeria components.
 */
public final class MoreMeterBinders {

    /**
     * Returns a new {@link MeterBinder} to observe Netty's {@link EventLoopGroup}s. The following stats are
     * currently exported per registered {@link MeterIdPrefix}.
     *
     * <ul>
     *   <li>"event.loop.num.workers" (gauge) - the total number of Netty's event loops</li>
     *   <li>"event.loop.pending.tasks" (gauge)
     *     - the total number of IO tasks waiting to be run on event loops</li>
     * </ul>
     */
    @UnstableApi
    public static MeterBinder eventLoopMetrics(EventLoopGroup eventLoopGroup, String name) {
        return new EventLoopMetrics(eventLoopGroup, name);
    }

    private MoreMeterBinders() {
    }
}
