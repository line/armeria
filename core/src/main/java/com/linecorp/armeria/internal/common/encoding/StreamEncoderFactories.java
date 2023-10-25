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

package com.linecorp.armeria.internal.common.encoding;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import com.aayushatharva.brotli4j.encoder.Encoder;

import com.linecorp.armeria.common.encoding.StreamDecoderFactory;

import io.netty.buffer.ByteBufOutputStream;
import io.netty.handler.codec.compression.Brotli;

public enum StreamEncoderFactories implements StreamEncoderFactory {
    BROTLI {
        @Override
        public String encodingHeaderValue() {
            return StreamDecoderFactory.brotli().encodingHeaderValue();
        }

        @Override
        public OutputStream newEncoder(ByteBufOutputStream os) {
            try {
                // We use 4 as the default level because it would save more bytes
                // than GZIP's default setting and compress data faster.
                // See: https://blogs.akamai.com/2016/02/understanding-brotlis-potential.html
                return new BrotliOutputStream(os, BROTLI_PARAMETERS);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Error writing brotli header. This should not happen with byte arrays.", e);
            }
        }
    },
    GZIP {
        @Override
        public String encodingHeaderValue() {
            return StreamDecoderFactory.gzip().encodingHeaderValue();
        }

        @Override
        public OutputStream newEncoder(ByteBufOutputStream os) {
            try {
                return new GZIPOutputStream(os, true);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Error writing gzip header. This should not happen with byte arrays.", e);
            }
        }
    },
    DEFLATE {
        @Override
        public String encodingHeaderValue() {
            return StreamDecoderFactory.deflate().encodingHeaderValue();
        }

        @Override
        public OutputStream newEncoder(ByteBufOutputStream os) {
            return new DeflaterOutputStream(os, true);
        }
    },
    SNAPPY {
        @Override
        public String encodingHeaderValue() {
            return StreamDecoderFactory.snappy().encodingHeaderValue();
        }

        @Override
        public OutputStream newEncoder(ByteBufOutputStream os) {
            return new SnappyFramedOutputStream(os.buffer());
        }
    };

    static {
        // Invoke to load Brotli native binary.
        Brotli.isAvailable();
    }

    private static final Encoder.Parameters BROTLI_PARAMETERS = new Encoder.Parameters().setQuality(4);
}
