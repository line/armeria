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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.encoding.StreamEncoderFactory;

import io.netty.handler.codec.compression.Brotli;

/**
 * Support utilities for dealing with HTTP encoding (e.g., gzip).
 */
final class HttpEncoders {

    static {
        // Invoke to load Brotli native binary.
        Brotli.isAvailable();
    }

    @Nullable
    static StreamEncoderFactory determineEncoder(
            Map<String, StreamEncoderFactory> headerToEncoderFactory, RequestHeaders headers
    ) {
        final String acceptEncoding = headers.get(HttpHeaderNames.ACCEPT_ENCODING);
        if (acceptEncoding == null) {
            return null;
        }

        return determineEncoder(headerToEncoderFactory, acceptEncoding);
    }

    // Adapted from netty's HttpContentCompressor.
    @Nullable
    private static StreamEncoderFactory determineEncoder(
            Map<String, StreamEncoderFactory> headerToEncoderFactory,
            String acceptEncoding) {
        float starQ = -1.0f;
        final Map<StreamEncoderFactory, Float> encoderFactoryToQ = new LinkedHashMap<>();

        /*
            // https://datatracker.ietf.org/doc/html/rfc7231#section-5.3.4
            // OWS is optional whitespace, i.e. *( SP / HTAB )
            Accept-Encoding = [
                ( "," / ( codings [ OWS ";" OWS "q=" qvalue ] ) )
                *( OWS "," [ OWS ( codings [ OWS ";" OWS "q=" qvalue ] ) ] )
            ]
        */

        for (String acceptEncodingElement : acceptEncoding.split(",")) {
            final String codings;
            float qValue;

            final int codingWeightSepIndex = acceptEncodingElement.indexOf(';');
            if (codingWeightSepIndex != -1) {
                // i.e. " \t br;  q=0.8"
                codings = acceptEncodingElement.substring(0, codingWeightSepIndex).trim();

                // We do not need to trim here. Float.parseFloat() will do it for us.
                final String weightRightPart = acceptEncodingElement
                        .substring(codingWeightSepIndex + 1);
                final int equalsPos = weightRightPart.indexOf('=');
                if (equalsPos != -1) {
                    try {
                        qValue = Float.parseFloat(weightRightPart.substring(equalsPos + 1));
                    } catch (NumberFormatException e) {
                        // Ignore encoding
                        qValue = 0.0f;
                    }
                } else {
                    qValue = 0.0f;
                }
            } else {
                // i.e. "  deflate\t" or "" because we have a leading comma
                codings = acceptEncodingElement.trim();
                qValue = 1.0f;
            }

            if (codings.contains("*")) {
                starQ = qValue;
            } else {
                if (codings.isEmpty()) {
                    continue;
                }
                final StreamEncoderFactory encodingFactory = headerToEncoderFactory.get(codings);

                if (encodingFactory != null) {
                    encoderFactoryToQ.put(encodingFactory, qValue);
                }
            }
        }

        if (!encoderFactoryToQ.isEmpty()) {
            final Entry<StreamEncoderFactory, Float> entry = Collections.max(encoderFactoryToQ.entrySet(),
                                                                             Entry.comparingByValue());
            if (entry.getValue() > 0.0f) {
                return entry.getKey();
            }
        }

        if (starQ > 0.0f) {
            for (final StreamEncoderFactory encoderFactory : headerToEncoderFactory.values()) {
                if (!encoderFactoryToQ.containsKey(encoderFactory)) {
                    return encoderFactory;
                }
            }
        }

        return null;
    }

    private HttpEncoders() {}
}
