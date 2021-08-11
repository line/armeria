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

import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;

public abstract class Http1KeepAliveHandler extends AbstractKeepAliveHandler {
    protected Http1KeepAliveHandler(Channel channel, String name, Timer keepAliveTimer, long idleTimeoutMillis,
                                    long pingIntervalMillis, long maxConnectionAgeMillis,
                                    long maxNumRequestsPerConnection) {
        super(channel, name, keepAliveTimer, idleTimeoutMillis, pingIntervalMillis, maxConnectionAgeMillis,
              maxNumRequestsPerConnection);
    }

    @Override
    public boolean isHttp2() {
        return false;
    }

    @Override
    public void onPingAck(long data) {
        throw new UnsupportedOperationException();
    }
}
