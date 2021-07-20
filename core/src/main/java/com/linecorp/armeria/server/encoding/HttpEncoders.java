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

package com.linecorp.armeria.server.encoding;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nullable;

import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;

import io.netty.handler.codec.compression.Brotli;

/**
 * Support utilities for dealing with HTTP encoding (e.g., gzip).
 */
final class HttpEncoders {

    @Nullable
    static HttpEncodingType getWrapperForRequest(HttpRequest request) {
        final String acceptEncoding = request.headers().get(HttpHeaderNames.ACCEPT_ENCODING);
        if (acceptEncoding == null) {
            return null;
        }
        return determineEncoding(acceptEncoding);
    }

    static OutputStream getEncodingOutputStream(HttpEncodingType encodingType, OutputStream out) {
        switch (encodingType) {
            case GZIP:
                try {
                    return new GZIPOutputStream(out, true);
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Error writing gzip header. This should not happen with byte arrays.", e);
                }
            case DEFLATE:
                return new DeflaterOutputStream(out, true);
            case BR:
                try {
                    return new BrotliOutputStream(out);
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Error writing brotli header. This should not happen with byte arrays.", e);
                }
            default:
                throw new IllegalArgumentException("Unexpected zlib type, this is a programming bug.");
        }
    }

    // Copied from netty's HttpContentCompressor.
    @Nullable
    @SuppressWarnings("FloatingPointEquality")
    private static HttpEncodingType determineEncoding(String acceptEncoding) {
        float starQ = -1.0f;
        float brQ = -1.0f;
        float gzipQ = -1.0f;
        float deflateQ = -1.0f;
        for (String encoding : acceptEncoding.split(",")) {
            float q = 1.0f;
            final int equalsPos = encoding.indexOf('=');
            if (equalsPos != -1) {
                try {
                    q = Float.parseFloat(encoding.substring(equalsPos + 1));
                } catch (NumberFormatException e) {
                    // Ignore encoding
                    q = 0.0f;
                }
            }
            if (encoding.contains("*")) {
                starQ = q;
            } else if (encoding.contains("br") && q > brQ && Brotli.isAvailable()) {
                brQ = q;
            } else if (encoding.contains("gzip") && q > gzipQ) {
                gzipQ = q;
            } else if (encoding.contains("deflate") && q > deflateQ) {
                deflateQ = q;
            }
        }
        if (brQ > 0.0f || gzipQ > 0.0f || deflateQ > 0.0f) {
            if (brQ != -1.0f && brQ >= gzipQ && brQ >= deflateQ) {
                return HttpEncodingType.BR;
            } else if (gzipQ != -1.0f && gzipQ >= deflateQ) {
                return HttpEncodingType.GZIP;
            } else {
                return HttpEncodingType.DEFLATE;
            }
        }
        if (starQ > 0.0f) {
            if (brQ == -1.0f && Brotli.isAvailable()) {
                return HttpEncodingType.BR;
            }
            if (gzipQ == -1.0f) {
                return HttpEncodingType.GZIP;
            }
            if (deflateQ == -1.0f) {
                return HttpEncodingType.DEFLATE;
            }
        }
        return null;
    }

    private HttpEncoders() {}
}
