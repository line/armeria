/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common;

import static com.google.common.net.MediaType.create;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.net.MediaType;

/**
 * Serialization format of a remote procedure call and its reply.
 */
public enum SerializationFormat {

    /**
     * No serialization format. Used when no serialization/deserialization is desired.
     */
    NONE("none", create("application", "x-none")),

    /**
     * Unknown serialization format. Used when some serialization format is desired but the server
     * failed to understand/recognize it.
     */
    UNKNOWN("unknown", create("application", "x-unknown")),

    /**
     * Thrift TBinary serialization format
     */
    THRIFT_BINARY("tbinary", create("application", "x-thrift").withParameter("protocol", "TBINARY")),

    /**
     * Thrift TCompact serialization format
     */
    THRIFT_COMPACT("tcompact", create("application", "x-thrift").withParameter("protocol", "TCOMPACT")),

    /**
     * Thrift TJSON serialization format
     */
    THRIFT_JSON("tjson", create("application", "x-thrift").withParameter("protocol", "TJSON")),

    /**
     * Thrift TText serialization format. This format is not optimized for performance or backwards
     * compatibility and should only be used in non-production use cases like debugging.
     */
    THRIFT_TEXT("ttext", create("application", "x-thrift").withParameter("protocol", "TTEXT"));

    private static final Set<SerializationFormat> THRIFT_FORMATS = Collections.unmodifiableSet(
            EnumSet.of(THRIFT_BINARY, THRIFT_COMPACT, THRIFT_JSON, THRIFT_TEXT));

    private static final Map<String, Optional<SerializationFormat>> PROTOCOL_TO_THRIFT_FORMATS;

    static {
        Map<String, Optional<SerializationFormat>> protocolToThriftFormats = new HashMap<>();
        for (SerializationFormat f : THRIFT_FORMATS) {
            protocolToThriftFormats.put(f.uriText(), Optional.of(f));
        }
        PROTOCOL_TO_THRIFT_FORMATS = Collections.unmodifiableMap(protocolToThriftFormats);
    }

    /**
     * Returns the set of all known Thrift serialization formats. This method is useful when determining if a
     * {@link SerializationFormat} is Thrift or not.
     * e.g. {@code if (SerializationFormat.ofThrift().contains(serFmt)) { ... }}
     */
    public static Set<SerializationFormat> ofThrift() {
        return THRIFT_FORMATS;
    }

    /**
     * Returns the serialization format corresponding to the passed in {@code mediaType}, or
     * {@link Optional#empty} if the media type is not recognized. {@code null} is treated as an unknown
     * mimetype.
     */
    public static Optional<SerializationFormat> fromMediaType(@Nullable String mediaType) {
        if (mediaType == null || mediaType.isEmpty()) {
            return Optional.empty();
        }

        final int semicolonIdx = mediaType.indexOf(';');
        final String paramPart;
        if (semicolonIdx >= 0) {
            paramPart = mediaType.substring(semicolonIdx).toLowerCase(Locale.US);
            mediaType = mediaType.substring(0, semicolonIdx).toLowerCase(Locale.US).trim();
        } else {
            paramPart = null;
            mediaType = mediaType.toLowerCase(Locale.US).trim();
        }

        if ("application/x-thrift".equals(mediaType)) {
            return fromThriftMediaType(paramPart);
        }

        if (NONE.mediaType().toString().equals(mediaType)) {
            return Optional.of(NONE);
        }

        return Optional.empty();
    }

    private static Optional<SerializationFormat> fromThriftMediaType(String params) {
        final String protocol = MimeTypeParams.find(params, "protocol");
        return PROTOCOL_TO_THRIFT_FORMATS.getOrDefault(protocol, Optional.empty());
    }

    private final String uriText;
    private final MediaType mediaType;

    SerializationFormat(String uriText, MediaType mediaType) {
        this.uriText = uriText;
        this.mediaType = mediaType;
    }

    /**
     * Returns the textual representation of this format for use in a {@link Scheme}.
     */
    public String uriText() {
        return uriText;
    }

    /**
     * Returns the {@link MediaType} of this format.
     */
    public MediaType mediaType() {
        return mediaType;
    }
}
