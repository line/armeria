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

package com.linecorp.armeria.server.jetty;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import com.linecorp.armeria.server.ServiceRequestContext;

class AsyncStreamingHandlerFunction implements Request.Handler {

    private static final Logger logger = LoggerFactory.getLogger(AsyncStreamingHandlerFunction.class);
    /**
     * A 128KiB full of characters.
     */
    private static final byte[] chunk = Strings.repeat("0123456789abcdef", 128 * 1024 / 16)
                                               .getBytes(StandardCharsets.US_ASCII);

    @Override
    public boolean handle(Request req, Response res, Callback callback) throws Exception {
        final ServiceRequestContext ctx = ServiceRequestContext.current();
        final int totalSize = Integer.parseInt(ctx.pathParam("totalSize"));
        final int chunkSize = Integer.parseInt(ctx.pathParam("chunkSize"));
        ctx.eventLoop().schedule(() -> {
            res.setStatus(200);
            stream(ctx, callback, res, totalSize, chunkSize);
        }, 500, TimeUnit.MILLISECONDS);
        return true;
    }

    private static void stream(ServiceRequestContext ctx, Callback callback, Response res,
                               int remainingBytes, int chunkSize) {
        final int bytesToWrite;
        final boolean lastChunk;
        if (remainingBytes <= chunkSize) {
            bytesToWrite = remainingBytes;
            lastChunk = true;
        } else {
            bytesToWrite = chunkSize;
            lastChunk = false;
        }

        try {
            res.write(lastChunk, ByteBuffer.wrap(chunk, 0, bytesToWrite), Callback.NOOP);
            if (lastChunk) {
                callback.succeeded();
            } else {
                ctx.eventLoop().execute(
                        () -> stream(ctx, callback, res,
                                     remainingBytes - bytesToWrite, chunkSize));
            }
        } catch (Exception e) {
            logger.warn("{} Unexpected exception:", ctx, e);
        }
    }

    @Override
    public InvocationType getInvocationType() {
        return InvocationType.NON_BLOCKING;
    }
}
