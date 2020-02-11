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

package com.linecorp.armeria.internal.common.grpc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.CharMatcher;
import com.google.common.io.BaseEncoding;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;

import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.netty.util.AsciiString;

/**
 * Utilities for working with {@link Metadata}.
 */
public final class MetadataUtil {

    private static final Logger logger = LoggerFactory.getLogger(MetadataUtil.class);

    private static final CharMatcher COMMA_MATCHER = CharMatcher.is(',');

    private static final BaseEncoding BASE64_ENCODING_OMIT_PADDING = BaseEncoding.base64().omitPadding();

    /**
     * Copies the headers in the gRPC {@link Metadata} to the Armeria {@link HttpHeadersBuilder}. Headers will
     * be added, without replacing any currently present in the {@link HttpHeaders}.
     */
    public static void fillHeaders(Metadata metadata, HttpHeadersBuilder builder) {
        if (InternalMetadata.headerCount(metadata) == 0) {
            return;
        }

        final byte[][] serializedMetadata = InternalMetadata.serialize(metadata);
        assert serializedMetadata.length % 2 == 0;

        for (int i = 0; i < serializedMetadata.length; i += 2) {
            final AsciiString name = new AsciiString(serializedMetadata[i], false);

            final byte[] valueBytes = serializedMetadata[i + 1];

            final String value;
            if (isBinary(name)) {
                value = BASE64_ENCODING_OMIT_PADDING.encode(valueBytes);
            } else if (isGrpcAscii(valueBytes)) {
                value = new String(valueBytes, StandardCharsets.US_ASCII);
            } else {
                logger.warn("Metadata name=" + name + ", value=" + Arrays.toString(valueBytes) +
                            " contains invalid ASCII characters, skipping.");
                continue;
            }
            builder.add(name, value);
        }
    }

    /**
     * Copies the headers in the Armeria {@link HttpHeaders} into a gRPC {@link Metadata}.
     */
    public static Metadata copyFromHeaders(HttpHeaders headers) {
        if (headers.isEmpty()) {
            return new Metadata();
        }

        int numHeaders = 0;
        for (Entry<AsciiString, String> entry : headers) {
            final AsciiString name = entry.getKey();
            final String value = entry.getValue();
            if (isBinary(name)) {
                numHeaders += COMMA_MATCHER.countIn(value) + 1;
            } else {
                numHeaders += 1;
            }
        }

        final byte[][] metadata = new byte[numHeaders * 2][];

        int i = 0;
        for (Entry<AsciiString, String> entry : headers) {
            final AsciiString name = entry.getKey();
            final String value = entry.getValue();
            final byte[] nameBytes = name.isEntireArrayUsed() ? name.array() : name.toByteArray();
            if (isBinary(name)) {
                int commaIndex = COMMA_MATCHER.indexIn(value);
                if (commaIndex == -1) {
                    metadata[i++] = nameBytes;
                    metadata[i++] = Base64.getDecoder().decode(value);
                } else {
                    int substringStartIndex = 0;
                    while (commaIndex != -1) {
                        final String substring = value.substring(substringStartIndex, commaIndex);
                        metadata[i++] = nameBytes;
                        metadata[i++] = Base64.getDecoder().decode(substring);

                        substringStartIndex = commaIndex + 1;
                        commaIndex = COMMA_MATCHER.indexIn(value, commaIndex + 1);
                    }
                    final String substring = value.substring(substringStartIndex);
                    metadata[i++] = nameBytes;
                    metadata[i++] = Base64.getDecoder().decode(substring);
                }
            } else {
                metadata[i++] = nameBytes;
                metadata[i++] = value.getBytes(StandardCharsets.UTF_8);
            }
        }

        return InternalMetadata.newMetadata(metadata);
    }

    private static boolean isBinary(AsciiString name) {
        return name.endsWith(Metadata.BINARY_HEADER_SUFFIX);
    }

    /**
     * Returns whether the metadata value is a valid gRPC header value, which is only allowed to be readable
     * ASCII.
     */
    private static boolean isGrpcAscii(byte[] value) {
        for (byte b : value) {
            if (b < 32 || b > 126) {
                return false;
            }
        }
        return true;
    }

    private MetadataUtil() {}
}
