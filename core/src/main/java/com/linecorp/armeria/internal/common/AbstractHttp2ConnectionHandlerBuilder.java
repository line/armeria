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
package com.linecorp.armeria.internal.common;

import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameLogger;
import io.netty.handler.codec.http2.Http2HeadersEncoder.SensitivityDetector;
import io.netty.handler.codec.http2.Http2PromisedRequestVerifier;
import io.netty.handler.codec.http2.Http2Settings;

@SuppressWarnings("ClassNameSameAsAncestorName")
public abstract class AbstractHttp2ConnectionHandlerBuilder<T extends AbstractHttp2ConnectionHandler,
        B extends AbstractHttp2ConnectionHandlerBuilder<T, B>>
        extends io.netty.handler.codec.http2.AbstractHttp2ConnectionHandlerBuilder<T, B> {

    private final Channel ch;

    protected AbstractHttp2ConnectionHandlerBuilder(Channel ch) {
        this.ch = ch;
    }

    protected final Channel channel() {
        return ch;
    }

    // Override the builder methods to increase the visibility.

    @Override
    public final T build() {
        return super.build();
    }

    @Override
    public final B initialSettings(Http2Settings settings) {
        return super.initialSettings(settings);
    }

    @Override
    public final B frameListener(Http2FrameListener frameListener) {
        return super.frameListener(frameListener);
    }

    @Override
    public final B gracefulShutdownTimeoutMillis(long gracefulShutdownTimeoutMillis) {
        return super.gracefulShutdownTimeoutMillis(gracefulShutdownTimeoutMillis);
    }

    @Override
    public final B server(boolean isServer) {
        return super.server(isServer);
    }

    @Override
    public final B maxReservedStreams(int maxReservedStreams) {
        return super.maxReservedStreams(maxReservedStreams);
    }

    @Override
    public final B connection(Http2Connection connection) {
        return super.connection(connection);
    }

    @Override
    public final B codec(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder) {
        return super.codec(decoder, encoder);
    }

    @Override
    public final B validateHeaders(boolean validateHeaders) {
        return super.validateHeaders(validateHeaders);
    }

    @Override
    public final B frameLogger(Http2FrameLogger frameLogger) {
        return super.frameLogger(frameLogger);
    }

    @Override
    public final B encoderEnforceMaxConcurrentStreams(boolean encoderEnforceMaxConcurrentStreams) {
        return super.encoderEnforceMaxConcurrentStreams(encoderEnforceMaxConcurrentStreams);
    }

    @Override
    public final B encoderEnforceMaxQueuedControlFrames(int maxQueuedControlFrames) {
        return super.encoderEnforceMaxQueuedControlFrames(maxQueuedControlFrames);
    }

    @Override
    public final B headerSensitivityDetector(SensitivityDetector headerSensitivityDetector) {
        return super.headerSensitivityDetector(headerSensitivityDetector);
    }

    @Override
    public final B encoderIgnoreMaxHeaderListSize(boolean ignoreMaxHeaderListSize) {
        return super.encoderIgnoreMaxHeaderListSize(ignoreMaxHeaderListSize);
    }

    @Override
    public final B promisedRequestVerifier(Http2PromisedRequestVerifier promisedRequestVerifier) {
        return super.promisedRequestVerifier(promisedRequestVerifier);
    }

    @Override
    public final B decoderEnforceMaxConsecutiveEmptyDataFrames(int maxConsecutiveEmptyFrames) {
        return super.decoderEnforceMaxConsecutiveEmptyDataFrames(maxConsecutiveEmptyFrames);
    }

    @Override
    public final B autoAckSettingsFrame(boolean autoAckSettings) {
        return super.autoAckSettingsFrame(autoAckSettings);
    }

    @Override
    public final B autoAckPingFrame(boolean autoAckPingFrame) {
        return super.autoAckPingFrame(autoAckPingFrame);
    }

    @Override
    public final B decoupleCloseAndGoAway(boolean decoupleCloseAndGoAway) {
        return super.decoupleCloseAndGoAway(decoupleCloseAndGoAway);
    }
}
