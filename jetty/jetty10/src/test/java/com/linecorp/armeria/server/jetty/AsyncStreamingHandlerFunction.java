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

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.servlet.AsyncContext;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import com.linecorp.armeria.internal.testing.SimpleChannelHandler.ThrowingBiConsumer;
import com.linecorp.armeria.server.ServiceRequestContext;

class AsyncStreamingHandlerFunction implements ThrowingBiConsumer<Request, Response> {

    private static final Logger logger = LoggerFactory.getLogger(AsyncStreamingHandlerFunction.class);
    /**
     * A 128KiB full of characters.
     */
    private static final byte[] chunk = Strings.repeat("0123456789abcdef", 128 * 1024 / 16)
                                               .getBytes(StandardCharsets.US_ASCII);

    @Override
    public void accept(Request req, Response res) {
        final ServiceRequestContext ctx = ServiceRequestContext.current();
        final int totalSize = Integer.parseInt(ctx.pathParam("totalSize"));
        final int chunkSize = Integer.parseInt(ctx.pathParam("chunkSize"));
        final AsyncContext asyncCtx = req.startAsync();
        ctx.eventLoop().schedule(() -> {
            res.setStatus(200);
            stream(ctx, asyncCtx, res, totalSize, chunkSize);
        }, 500, TimeUnit.MILLISECONDS);
    }

    private static void stream(ServiceRequestContext ctx, AsyncContext asyncCtx, Response res,
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
            final OutputStream out = res.getOutputStream();
            out.write(chunk, 0, bytesToWrite);
            if (lastChunk) {
                out.close();
            } else {
                ctx.eventLoop().execute(
                        () -> stream(ctx, asyncCtx, res,
                                     remainingBytes - bytesToWrite, chunkSize));
            }
        } catch (Exception e) {
            logger.warn("{} Unexpected exception:", ctx, e);
        } finally {
            if (lastChunk) {
                asyncCtx.complete();
            }
        }
    }
}
