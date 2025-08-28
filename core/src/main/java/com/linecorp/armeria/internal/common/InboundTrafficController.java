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

package com.linecorp.armeria.internal.common;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.math.IntMath;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Stream;

public final class InboundTrafficController extends AtomicInteger {

    private static final Logger logger = LoggerFactory.getLogger(InboundTrafficController.class);

    private static final long serialVersionUID = 420503276551000218L;

    private static final InboundTrafficController DISABLED = new InboundTrafficController(null, null,
                                                                                          0, 0);
    private static int numDeferredReads;

    public static int numDeferredReads() {
        return numDeferredReads;
    }

    public static InboundTrafficController ofHttp1(Channel channel) {
        return new InboundTrafficController(channel, null, 128 * 1024, 64 * 1024);
    }

    // TODO(ikhoon): Add OutboundTrafficController.ofHttp2() method to apply backpressure to outbound traffic
    //               using HTTP/2 flow control.
    public static InboundTrafficController ofHttp2(Channel channel, Http2ConnectionDecoder decoder,
                                                   int connectionWindowSize) {
        // Compensate for protocol overhead traffic incurred by frame headers, etc.
        // This is a very rough estimate, but it should not hurt.
        connectionWindowSize = IntMath.saturatedAdd(connectionWindowSize, 1024);

        final int highWatermark = connectionWindowSize;
        final int lowWatermark = highWatermark >>> 1;
        return new InboundTrafficController(channel, decoder, highWatermark, lowWatermark);
    }

    public static InboundTrafficController disabled() {
        return DISABLED;
    }

    @Nullable
    private final Channel channel;
    @Nullable
    private final ChannelConfig cfg;
    @Nullable
    private final Http2ConnectionDecoder decoder;
    private final int highWatermark;
    private final int lowWatermark;
    private volatile boolean suspended;

    private InboundTrafficController(@Nullable Channel channel,
                                     @Nullable Http2ConnectionDecoder decoder,
                                     int highWatermark, int lowWatermark) {
        this.channel = channel;
        cfg = channel != null ? channel.config() : null;
        this.decoder = decoder;
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

    public void dec(int streamId, int numConsumedBytes) {
        if (decoder != null) {
            assert channel != null;
            if (channel.eventLoop().inEventLoop()) {
                consumeHttp2Bytes(streamId, numConsumedBytes);
            } else {
                channel.eventLoop().execute(() -> consumeHttp2Bytes(streamId, numConsumedBytes));
            }
        }
        final int oldValue = getAndAdd(-numConsumedBytes);
        if (oldValue > lowWatermark && oldValue - numConsumedBytes <= lowWatermark) {
            // Just went below low watermark
            if (cfg != null) {
                cfg.setAutoRead(true);
                suspended = false;
            }
        }
    }

    private void consumeHttp2Bytes(int streamId, int numConsumedBytes) {
        assert decoder != null && channel != null;
        final Http2Stream stream = decoder.connection().stream(streamId);
        if (stream != null) {
            try {
                if (decoder.flowController().consumeBytes(stream, numConsumedBytes)) {
                    channel.flush();
                }
            } catch (Http2Exception e) {
                logger.warn("{} Failed to consume bytes from stream {}", channel, streamId, e);
            }
        } else if (!decoder.connection().streamMayHaveExisted(streamId)) {
            logger.warn("{} Stream {} not found when consuming bytes", channel, streamId);
        }
    }

    @Nullable
    @VisibleForTesting
    public Http2ConnectionDecoder decoder() {
        return decoder;
    }

    @VisibleForTesting
    public boolean isSuspended() {
        return suspended;
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
