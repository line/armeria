/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.common.MediaType.OCTET_STREAM;
import static com.linecorp.armeria.common.MediaType.create;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Serialization format of a remote procedure call and its reply.
 */
public final class SerializationFormat implements Comparable<SerializationFormat> {

    private static final Logger logger = LoggerFactory.getLogger(SerializationFormat.class);

    private static final BiMap<String, SerializationFormat> uriTextToFormats;
    private static final Set<SerializationFormat> values;

    /**
     * No serialization format. Used when no serialization/deserialization is desired.
     */
    public static final SerializationFormat NONE;

    /**
     * Serialization format for WebSocket.
     */
    @UnstableApi
    public static final SerializationFormat WS;

    /**
     * Unknown serialization format. Used when some serialization format is desired but the server
     * failed to understand or recognize it.
     */
    public static final SerializationFormat UNKNOWN;

    static {
        final BiMap<String, SerializationFormat> mutableUriTextToFormats = HashBiMap.create();
        final Multimap<MediaType, SerializationFormat> mutableSimplifiedMediaTypeToFormats =
                HashMultimap.create();

        // Register the core formats first.
        NONE = register(mutableUriTextToFormats, mutableSimplifiedMediaTypeToFormats,
                        new SerializationFormatProvider.Entry("none", create("application", "x-none")));
        // WebSocket does not use media type but set it with the application/octet-stream which represents
        // for arbitrary binary data.
        WS = register(mutableUriTextToFormats, mutableSimplifiedMediaTypeToFormats,
                      new SerializationFormatProvider.Entry("ws", OCTET_STREAM));
        UNKNOWN = register(mutableUriTextToFormats, mutableSimplifiedMediaTypeToFormats,
                           new SerializationFormatProvider.Entry(
                                   "unknown", create("application", "x-unknown")));

        // Load all serialization formats from the providers.
        final List<SerializationFormatProvider> providers = ImmutableList.copyOf(
                ServiceLoader.load(SerializationFormatProvider.class,
                                   SerializationFormatProvider.class.getClassLoader()));
        if (!providers.isEmpty()) {
            logger.debug("Available {}s: {}", SerializationFormatProvider.class.getSimpleName(), providers);

            providers.forEach(p -> p.entries().forEach(e -> register(mutableUriTextToFormats,
                                                                     mutableSimplifiedMediaTypeToFormats, e)));
        }

        uriTextToFormats = ImmutableBiMap.copyOf(mutableUriTextToFormats);
        values = uriTextToFormats.values();
    }

    private static SerializationFormat register(
            BiMap<String, SerializationFormat> uriTextToFormats,
            Multimap<MediaType, SerializationFormat> simplifiedMediaTypeToFormats,
            SerializationFormatProvider.Entry entry) {

        checkState(!uriTextToFormats.containsKey(entry.uriText),
                   "serialization format registered already: %s", entry.uriText);

        final SerializationFormat value = new SerializationFormat(
                entry.uriText, entry.primaryMediaType, entry.mediaTypes);
        for (MediaType type : entry.mediaTypes) {
            checkMediaType(simplifiedMediaTypeToFormats, type);
        }

        uriTextToFormats.put(entry.uriText, value);
        for (MediaType type : entry.mediaTypes) {
            simplifiedMediaTypeToFormats.put(type.withoutParameters(), value);
        }

        return value;
    }

    /**
     * Makes sure the specified {@link MediaType} or its compatible one is registered already.
     */
    private static void checkMediaType(Multimap<MediaType, SerializationFormat> simplifiedMediaTypeToFormats,
                                       MediaType mediaType) {
        final MediaType simplifiedMediaType = mediaType.withoutParameters();
        for (SerializationFormat format : simplifiedMediaTypeToFormats.get(simplifiedMediaType)) {
            for (MediaType registeredMediaType : format.mediaTypes()) {
                checkState(!registeredMediaType.is(mediaType) && !mediaType.is(registeredMediaType),
                           "media type registered already: %s", mediaType);
            }
        }
    }

    /**
     * Returns all available {@link SerializationFormat}s.
     */
    public static Set<SerializationFormat> values() {
        return values;
    }

    /**
     * Returns the {@link SerializationFormat} with the specified {@link #uriText()}.
     *
     * @throws IllegalArgumentException if there's no such {@link SerializationFormat}
     */
    public static SerializationFormat of(String uriText) {
        uriText = Ascii.toLowerCase(requireNonNull(uriText, "uriText"));
        final SerializationFormat value = uriTextToFormats.get(uriText);
        checkArgument(value != null, "unknown serialization format: %s", uriText);
        return value;
    }

    /**
     * Finds the {@link SerializationFormat} with the specified {@link #uriText()}.
     */
    @Nullable
    public static SerializationFormat find(String uriText) {
        uriText = Ascii.toLowerCase(requireNonNull(uriText, "uriText"));
        return uriTextToFormats.get(uriText);
    }

    /**
     * Finds the {@link SerializationFormat} which is accepted by any of the specified media ranges.
     */
    @Nullable
    public static SerializationFormat find(MediaType... ranges) {
        requireNonNull(ranges, "ranges");
        if (ranges.length == 0) {
            return null;
        }

        for (SerializationFormat f : values()) {
            if (f.isAccepted(Arrays.asList(ranges))) {
                return f;
            }
        }

        return null;
    }

    private final String uriText;
    private final MediaType primaryMediaType;
    private final MediaTypeSet mediaTypes;

    private SerializationFormat(String uriText, MediaType primaryMediaType, MediaTypeSet mediaTypes) {
        this.uriText = uriText;
        this.primaryMediaType = primaryMediaType;
        this.mediaTypes = mediaTypes;
    }

    /**
     * Returns the textual representation of this format for use in a {@link Scheme}.
     */
    public String uriText() {
        return uriText;
    }

    /**
     * Returns the primary {@link MediaType} of this format.
     */
    public MediaType mediaType() {
        return primaryMediaType;
    }

    /**
     * Returns the media types accepted by this format.
     */
    public MediaTypeSet mediaTypes() {
        return mediaTypes;
    }

    /**
     * Returns whether this {@link SessionProtocol} needs to establish a new connection instead of acquiring it
     * from the connection pool.
     */
    public boolean requiresNewConnection(SessionProtocol protocol) {
        return this == WS && !protocol.isMultiplex();
    }

    /**
     * Returns whether the specified media range is accepted by any of the {@link #mediaTypes()}
     * defined by this format.
     */
    public boolean isAccepted(MediaType range) {
        requireNonNull(range, "range");
        return mediaTypes.match(range) != null;
    }

    /**
     * Returns whether any of the specified media ranges is accepted by any of the {@link #mediaTypes()}
     * defined by this format.
     */
    public boolean isAccepted(MediaType first, MediaType... rest) {
        requireNonNull(first, "first");
        requireNonNull(rest, "rest");
        return isAccepted(Lists.asList(first, rest));
    }

    /**
     * Returns whether any of the specified media ranges is accepted by any of the {@link #mediaTypes()}
     * defined by this format.
     */
    public boolean isAccepted(Iterable<MediaType> ranges) {
        requireNonNull(ranges, "ranges");
        return mediaTypes.match(ranges) != null;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        return this == obj;
    }

    @Override
    public int compareTo(SerializationFormat o) {
        return uriText.compareTo(o.uriText);
    }

    @Override
    public String toString() {
        return uriText;
    }
}
