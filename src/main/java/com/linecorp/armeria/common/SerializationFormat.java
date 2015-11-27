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

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Serialization format of a remote procedure call and its reply.
 */
public enum SerializationFormat {

    /**
     * No serialization format. Used when no serialization/deserialization is desired or when the server
     * failed to determine the serialization format.
     */
    NONE("none", "none/none"),

    /**
     * Thrift TBinary serialization format
     */
    THRIFT_BINARY("tbinary", "application/x-thrift; protocol=TBINARY"),

    /**
     * Thrift TCompact serialization format
     */
    THRIFT_COMPACT("tcompact", "application/x-thrift; protocol=TCOMPACT"),

    /**
     * Thrift TJSON serialization format
     */
    THRIFT_JSON("tjson", "application/x-thrift; protocol=TJSON"),

    /**
     * Thrift TText serialization format. This format is not optimized for performance or backwards
     * compatibility and should only be used in non-production use cases like debugging.
     */
    THRIFT_TEXT("ttext", "application/x-thrift; protocol=TTEXT");

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
     * Returns the serialization format corresponding to the passed in {@code mimeType}, or
     * {@link Optional#empty} if the mimetype is not recognized. {@code null} is treated as an unknown
     * mimetype.
     */
    public static Optional<SerializationFormat> fromMimeType(@Nullable String mimeType) {
        if (mimeType == null || mimeType.isEmpty()) {
            return Optional.empty();
        }

        final int semicolonIdx = mimeType.indexOf(';');
        final String paramPart;
        if (semicolonIdx >= 0) {
            paramPart = mimeType.substring(semicolonIdx).toLowerCase(Locale.US);
            mimeType = mimeType.substring(0, semicolonIdx).toLowerCase(Locale.US).trim();
        } else {
            paramPart = null;
            mimeType = mimeType.toLowerCase(Locale.US).trim();
        }

        if ("application/x-thrift".equals(mimeType)) {
            return fromThriftMimeType(paramPart);
        }

        if (NONE.mimeType().equals(mimeType)) {
            return Optional.of(NONE);
        }

        return Optional.empty();
    }

    private static Optional<SerializationFormat> fromThriftMimeType(String params) {
        final String protocol = MimeTypeParams.find(params, "protocol");
        if (protocol == null) {
            return Optional.empty();
        }

        final Optional<SerializationFormat> serFmt = PROTOCOL_TO_THRIFT_FORMATS.get(protocol);
        return serFmt != null ? serFmt : Optional.empty();
    }

    private final String uriText;
    private final String mimeType;

    SerializationFormat(String uriText, String mimeType) {
        this.uriText = uriText;
        this.mimeType = mimeType;
    }

    /**
     * Returns the textual representation of this format for use in a {@link Scheme}.
     */
    public String uriText() {
        return uriText;
    }

    /**
     * Returns the MIME type of this format.
     */
    public String mimeType() {
        return mimeType;
    }
}
