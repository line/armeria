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

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.encoding.StreamEncoderFactories;
import com.linecorp.armeria.internal.common.encoding.StreamEncoderFactory;

import io.netty.handler.codec.compression.Brotli;

/**
 * Support utilities for dealing with HTTP encoding (e.g., gzip).
 */
final class HttpEncoders {

    static {
        // Invoke to load Brotli native binary.
        Brotli.isAvailable();
    }

    static final Set<StreamEncoderFactories> ALL_ENCODER_FACTORIES =
            Collections.unmodifiableSet(EnumSet.allOf(StreamEncoderFactories.class));

    @Nullable
    static StreamEncoderFactory getEncoderFactory(
            RequestHeaders headers, Set<StreamEncoderFactories> encoderFactoryAllowList
    ) {
        final String acceptEncoding = headers.get(HttpHeaderNames.ACCEPT_ENCODING);
        if (acceptEncoding == null) {
            return null;
        }
        return determineEncoder(encoderFactoryAllowList, acceptEncoding);
    }

    // Copied from netty's HttpContentCompressor.
    @Nullable
    private static StreamEncoderFactory determineEncoder(Set<StreamEncoderFactories> encoderFactoryAllowList,
                                                         String acceptEncoding) {
        float starQ = -1.0f;
        final Map<StreamEncoderFactory, Float> encodings = new LinkedHashMap<>();
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
            } else if (encoding.contains("br") && Brotli.isAvailable()) {
                encodings.put(StreamEncoderFactories.BROTLI, q);
            } else if (encoding.contains("gzip")) {
                encodings.put(StreamEncoderFactories.GZIP, q);
            } else if (encoding.contains("deflate")) {
                encodings.put(StreamEncoderFactories.DEFLATE, q);
            } else if (encoding.contains("x-snappy-framed")) {
                encodings.put(StreamEncoderFactories.SNAPPY, q);
            }
        }

        encodings.keySet().retainAll(encoderFactoryAllowList);

        if (!encodings.isEmpty()) {
            final Entry<StreamEncoderFactory, Float> entry = Collections.max(encodings.entrySet(),
                                                                             Entry.comparingByValue());
            if (entry.getValue() > 0.0f) {
                return entry.getKey();
            }
        }

        if (starQ > 0.0f) {
            if (encoderFactoryAllowList.contains(StreamEncoderFactories.BROTLI) &&
                !encodings.containsKey(StreamEncoderFactories.BROTLI) && Brotli.isAvailable()) {
                return StreamEncoderFactories.BROTLI;
            }
            if (encoderFactoryAllowList.contains(StreamEncoderFactories.GZIP) &&
                !encodings.containsKey(StreamEncoderFactories.GZIP)) {
                return StreamEncoderFactories.GZIP;
            }
            if (encoderFactoryAllowList.contains(StreamEncoderFactories.DEFLATE) &&
                !encodings.containsKey(StreamEncoderFactories.DEFLATE)) {
                return StreamEncoderFactories.DEFLATE;
            }
            if (encoderFactoryAllowList.contains(StreamEncoderFactories.SNAPPY) &&
                !encodings.containsKey(StreamEncoderFactories.SNAPPY)) {
                return StreamEncoderFactories.SNAPPY;
            }
        }
        return null;
    }

    private HttpEncoders() {}
}
