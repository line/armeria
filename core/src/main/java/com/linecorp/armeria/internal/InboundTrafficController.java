/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.internal;

import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;

public final class InboundTrafficController extends AtomicInteger {

    private static final long serialVersionUID = 420503276551000218L;

    private static int numDeferredReads;

    public static int numDeferredReads() {
        return numDeferredReads;
    }

    private final ChannelConfig cfg;
    private final int highWatermark;
    private final int lowWatermark;
    private volatile boolean suspended;

    public InboundTrafficController(@Nullable Channel channel) {
        this(channel, 128 * 1024, 64 * 1024);
    }

    public InboundTrafficController(@Nullable Channel channel, int highWatermark, int lowWatermark) {
        cfg = channel != null ? channel.config() : null;
        this.highWatermark = highWatermark;
        this.lowWatermark = lowWatermark;
    }

    public void inc(int numProducedBytes) {
        final int oldValue = getAndAdd(numProducedBytes);
        if (oldValue <= highWatermark && oldValue + numProducedBytes > highWatermark) {
            // Just went above high watermark
            if (cfg != null) {
                cfg.setAutoRead(false);
                numDeferredReads++;
                suspended = true;
            }
        }
    }

    public void dec(int numConsumedBytes) {
        final int oldValue = getAndAdd(-numConsumedBytes);
        if (oldValue > lowWatermark && oldValue - numConsumedBytes <= lowWatermark) {
            // Just went below high watermark
            if (cfg != null) {
                cfg.setAutoRead(true);
                suspended = false;
            }
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("suspended", suspended)
                          .add("unconsumed", get())
                          .add("watermarks", highWatermark + "/" + lowWatermark)
                          .toString();
    }
}
