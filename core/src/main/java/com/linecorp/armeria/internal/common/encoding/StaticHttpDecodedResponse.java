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

package com.linecorp.armeria.internal.common.encoding;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nonnull;

import com.linecorp.armeria.client.encoding.StreamDecoder;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * An {@link HttpResponse} that applies HTTP decoding using the {@link StreamDecoder} created with this.
 */
public final class StaticHttpDecodedResponse extends AbstractHttpDecodedResponse {

    private final StreamDecoder decoder;
    @Nullable
    private final MediaType contentType;

    public StaticHttpDecodedResponse(HttpResponse delegate, StreamDecoder decoder,
                                     @Nullable MediaType contentType) {
        super(requireNonNull(delegate, "delegate"));
        this.decoder = requireNonNull(decoder, "decoder");
        this.contentType = contentType;
    }

    @Override
    protected HttpObject filter(HttpObject obj) {
        if (obj instanceof HttpData) {
            return decoder.decode((HttpData) obj);
        }

        if (obj instanceof ResponseHeaders) {
            final ResponseHeaders responseHeaders = (ResponseHeaders) obj;
            if (!responseHeaders.status().isInformational()) {
                final ResponseHeadersBuilder builder = responseHeaders.toBuilder();
                builder.remove(HttpHeaderNames.CONTENT_LENGTH);
                if (contentType == null) {
                    builder.remove(HttpHeaderNames.CONTENT_TYPE);
                } else {
                    builder.contentType(contentType);
                }
                return builder.build();
            }
        }
        return obj;
    }

    @Nonnull
    @Override
    StreamDecoder decoder() {
        return decoder;
    }
}
