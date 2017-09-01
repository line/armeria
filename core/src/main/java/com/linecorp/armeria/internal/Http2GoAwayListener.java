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

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2ConnectionAdapter;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Stream;

/**
 * A {@link Http2ConnectionAdapter} that logs the received GOAWAY frames and makes sure disconnection.
 */
public class Http2GoAwayListener extends Http2ConnectionAdapter {

    private static final Logger logger = LoggerFactory.getLogger(Http2GoAwayListener.class);

    private final Channel ch;
    private boolean goAwaySent;

    public Http2GoAwayListener(Channel ch) {
        this.ch = ch;
    }

    @Override
    public void onGoAwaySent(int lastStreamId, long errorCode, ByteBuf debugData) {
        goAwaySent = true;
        onGoAway("Sent", lastStreamId, errorCode, debugData);
    }

    @Override
    public void onGoAwayReceived(int lastStreamId, long errorCode, ByteBuf debugData) {
        onGoAway("Received", lastStreamId, errorCode, debugData);

        // Send a GOAWAY back to the peer and close the connection gracefully if we did not send GOAWAY yet.
        // This will make sure that the connection is always closed after receiving GOAWAY,
        // because otherwise we have to wait until the peer who sent GOAWAY to us closes the connection.
        if (!goAwaySent) {
            ch.close();
        }
    }

    private void onGoAway(String sentOrReceived, int lastStreamId, long errorCode, ByteBuf debugData) {
        if (errorCode != Http2Error.NO_ERROR.code()) {
            if (logger.isWarnEnabled()) {
                logger.warn("{} {} a GOAWAY frame: lastStreamId={}, errorCode={}, debugData=\"{}\"",
                            ch, sentOrReceived, lastStreamId, errorStr(errorCode),
                            debugData.toString(StandardCharsets.UTF_8));
            }
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("{} {} a GOAWAY frame: lastStreamId={}, errorCode=NO_ERROR",
                             ch, sentOrReceived, lastStreamId);
            }
        }
    }

    private static String errorStr(long errorCode) {
        final Http2Error error = Http2Error.valueOf(errorCode);
        return error != null ? error.toString() + '(' + errorCode + ')'
                             : "UNKNOWN(" + errorCode + ')';
    }

    @Override
    public void onStreamRemoved(Http2Stream stream) {
        if (stream.id() == 1) {
            logger.debug("{} HTTP/2 upgrade stream removed: {}", ch, stream.state());
        }
    }
}
