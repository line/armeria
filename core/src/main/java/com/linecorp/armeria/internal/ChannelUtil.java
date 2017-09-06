/*
 * Copyright 2017 LINE Corporation
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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.ImmutableList;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;

public final class ChannelUtil {

    public static CompletableFuture<Void> close(Iterable<? extends Channel> channels) {
        final List<Channel> channelsCopy = ImmutableList.copyOf(channels);
        if (channelsCopy.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        final AtomicInteger numChannelsToClose = new AtomicInteger(channelsCopy.size());
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final ChannelFutureListener listener = unused -> {
            if (numChannelsToClose.decrementAndGet() == 0) {
                future.complete(null);
            }
        };

        for (Channel ch : channelsCopy) {
            ch.close().addListener(listener);
        }

        return future;
    }

    private ChannelUtil() {}
}
