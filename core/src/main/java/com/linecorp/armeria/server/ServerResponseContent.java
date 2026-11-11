/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.encoding.StreamEncoderFactory;
import com.linecorp.armeria.internal.server.HttpEncoders;
import com.linecorp.armeria.server.encoding.Compression;
import com.linecorp.armeria.server.logging.ContentPreview;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;

/**
 * Processes the response selected by the HTTP writer, including replacement error responses.
 */
final class ServerResponseContent {
    private final ServiceRequestContext ctx;
    private final ContentPreview previewConfig;
    private final Compression compression;
    @Nullable
    private HttpContentPreviewer previewer;
    @Nullable
    private ByteBufOutputStream encoded;
    @Nullable
    private OutputStream encoder;
    private boolean compressionDisabled;

    ServerResponseContent(ServiceRequestContext ctx) {
        this.ctx = ctx;
        previewConfig = ctx.config().options().get(ServerOptions.CONTENT_PREVIEW);
        compression = ctx.config().options().get(ServerOptions.COMPRESSION);
    }

    ResponseHeaders prepareHeaders(ResponseHeaders headers, boolean endOfStream) {
        if (previewConfig.factory() != null && !headers.contains(HttpHeaderNames.CONTENT_ENCODING)) {
            previewer = new HttpContentPreviewer(ctx, previewConfig, headers, false);
        }
        if (compressionDisabled || endOfStream || ctx.method() == HttpMethod.HEAD ||
            headers.status().isContentAlwaysEmpty() || headers.status().code() == 206 ||
            headers.contains(HttpHeaderNames.CONTENT_ENCODING) || compression.encoderFactories().isEmpty()) {
            return headers;
        }
        final MediaType contentType;
        try {
            contentType = headers.contentType();
        } catch (IllegalArgumentException ignored) {
            return headers;
        }
        if (contentType != null && (contentType.belongsTo(MediaType.EVENT_STREAM) ||
                                    !compression.encodableContentTypes().test(contentType))) {
            return headers;
        }
        if (!compression.encodableRequestHeaders().test(ctx.request().headers()) ||
            headers.contentLength() >= 0 &&
            headers.contentLength() < compression.minBytesToForceChunkedEncoding()) {
            return headers;
        }
        final Map<String, StreamEncoderFactory> factories = new LinkedHashMap<>();
        for (StreamEncoderFactory factory : compression.encoderFactories()) {
            factories.put(factory.encodingHeaderValue(), factory);
        }
        final StreamEncoderFactory factory = HttpEncoders.determineEncoder(factories, ctx.request().headers());
        if (factory == null) {
            return headers;
        }
        encoded = new ByteBufOutputStream(ctx.alloc().buffer());
        encoder = requireNonNull(factory.newEncoder(encoded), "encoder");
        final ResponseHeadersBuilder builder = headers.toBuilder()
                .removeAndThen(HttpHeaderNames.CONTENT_LENGTH)
                .set(HttpHeaderNames.CONTENT_ENCODING, factory.encodingHeaderValue());
        boolean variesByEncoding = false;
        for (String vary : headers.getAll(HttpHeaderNames.VARY)) {
            for (String name : vary.split(",")) {
                if (name.trim().equals("*") || name.trim().equalsIgnoreCase("accept-encoding")) {
                    variesByEncoding = true;
                }
            }
        }
        if (!variesByEncoding) {
            builder.add(HttpHeaderNames.VARY, HttpHeaderNames.ACCEPT_ENCODING.toString());
        }
        return builder.build();
    }

    boolean isEncoding() {
        return encoder != null;
    }

    HttpData process(HttpData data, boolean endOfStream) throws IOException {
        if (encoder == null) {
            if (previewer != null) {
                previewer.onData(data);
            }
            return data;
        }
        try {
            encoder.write(data.array());
            if (endOfStream) {
                encoder.close();
                encoder = null;
            } else {
                encoder.flush();
            }
            if (previewer != null) {
                previewer.onData(data);
            }
            return drain();
        } finally {
            data.close();
        }
    }

    HttpData finishEncoding() throws IOException {
        if (encoder == null) {
            return HttpData.empty();
        }
        encoder.close();
        encoder = null;
        return drain();
    }

    private HttpData drain() {
        assert encoded != null;
        final ByteBuf buffer = encoded.buffer();
        final HttpData data = HttpData.wrap(ByteBufUtil.getBytes(buffer));
        buffer.clear();
        return data;
    }

    void disableCompression() {
        releaseEncoder();
        compressionDisabled = true;
        // No headers have been written; the replacement response gets its own previewer.
        previewer = null;
    }

    void finish() {
        if (previewer != null) {
            previewer.finish();
        } else if (previewConfig.factory() != null) {
            ctx.logBuilder().responseContentPreview(null);
        }
        releaseEncoder();
    }

    private void releaseEncoder() {
        if (encoder != null) {
            try {
                encoder.close();
            } catch (Exception ignored) {
                // The exchange has already failed. Discard any remaining encoded content.
            } finally {
                encoder = null;
            }
        }
        if (encoded != null) {
            encoded.buffer().release();
            encoded = null;
        }
    }
}
