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
import static com.linecorp.armeria.common.MediaType.create;
import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.Ascii;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;

/**
 * Serialization format of a remote procedure call and its reply.
 */
public final class SerializationFormat implements Comparable<SerializationFormat> {

    private static final BiMap<String, SerializationFormat> uriTextToFormats;
    private static final Set<SerializationFormat> values;

    /**
     * No serialization format. Used when no serialization/deserialization is desired.
     */
    public static final SerializationFormat NONE;

    /**
     * Unknown serialization format. Used when some serialization format is desired but the server
     * failed to understand or recognize it.
     */
    public static final SerializationFormat UNKNOWN;

    /**
     * Thrift TBinary serialization format.
     *
     * @deprecated Use {@code ThriftSerializationFormats.BINARY}. Note that the value of this field will be
     *             {@code null} if {@code armeria-thrift} module is not loaded.
     */
    @Deprecated
    public static final SerializationFormat THRIFT_BINARY;

    /**
     * Thrift TCompact serialization format.
     *
     * @deprecated Use {@code ThriftSerializationFormats.COMPACT}. Note that the value of this field will be
     *             {@code null} if {@code armeria-thrift} module is not loaded.
     */
    @Deprecated
    public static final SerializationFormat THRIFT_COMPACT;

    /**
     * Thrift TJSON serialization format.
     *
     * @deprecated Use {@code ThriftSerializationFormats.JSON}. Note that the value of this field will be
     *             {@code null} if {@code armeria-thrift} module is not loaded.
     */
    @Deprecated
    public static final SerializationFormat THRIFT_JSON;

    /**
     * Thrift TText serialization format.
     *
     * @deprecated Use {@code ThriftSerializationFormats.TEXT}. Note that the value of this field will be
     *             {@code null} if {@code armeria-thrift} module is not loaded.
     */
    @Deprecated
    public static final SerializationFormat THRIFT_TEXT;

    private static final Set<SerializationFormat> THRIFT_FORMATS;

    static {
        BiMap<String, SerializationFormat> mutableUriTextToFormats = HashBiMap.create();
        Multimap<MediaType, SerializationFormat> mutableSimplifiedMediaTypeToFormats = HashMultimap.create();

        // Register the core formats first.
        NONE = register(mutableUriTextToFormats, mutableSimplifiedMediaTypeToFormats,
                        new SerializationFormatProvider.Entry("none", create("application", "x-none")));
        UNKNOWN = register(mutableUriTextToFormats, mutableSimplifiedMediaTypeToFormats,
                           new SerializationFormatProvider.Entry(
                                   "unknown", create("application", "x-unknown")));

        // Load all serialization formats from the providers.
        ServiceLoader.load(SerializationFormatProvider.class,
                           SerializationFormatProvider.class.getClassLoader())
                     .forEach(p -> p.entries().forEach(e -> register(mutableUriTextToFormats,
                                                                     mutableSimplifiedMediaTypeToFormats, e)));

        uriTextToFormats = ImmutableBiMap.copyOf(mutableUriTextToFormats);
        values = uriTextToFormats.values();

        // Backward compatibility stuff
        SerializationFormat tbinary = null;
        SerializationFormat tcompact = null;
        SerializationFormat tjson = null;
        SerializationFormat ttext = null;
        Set<SerializationFormat> thriftFormats = null;
        try {
            tbinary = of("tbinary");
            tcompact = of("tcompact");
            tjson = of("tjson");
            ttext = of("ttext");
            thriftFormats = ImmutableSet.of(tbinary, tcompact, tjson, ttext);
        } catch (IllegalArgumentException e) {
            // ThriftSerializationFormatProvider is not loaded.
        }

        THRIFT_BINARY = tbinary;
        THRIFT_COMPACT = tcompact;
        THRIFT_JSON = tjson;
        THRIFT_TEXT = ttext;
        THRIFT_FORMATS = thriftFormats;
    }

    private static SerializationFormat register(
            BiMap<String, SerializationFormat> uriTextToFormats,
            Multimap<MediaType, SerializationFormat> simplifiedMediaTypeToFormats,
            SerializationFormatProvider.Entry entry) {

        checkState(!uriTextToFormats.containsKey(entry.uriText),
                   "serialization format registered already: ", entry.uriText);

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
     * Returns the set of all known Thrift serialization formats.
     *
     * @deprecated Use {@code ThriftSerializationFormats.values()}.
     *
     * @throws IllegalStateException if {@code armeria-thrift} module is not loaded
     */
    @Deprecated
    public static Set<SerializationFormat> ofThrift() {
        if (THRIFT_FORMATS == null) {
            throw new IllegalStateException("Thrift support not available");
        }

        return THRIFT_FORMATS;
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
                           "media type registered already: ", mediaType);
            }
        }
    }

    /**
     * Returns all available {@link SessionProtocol}s.
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
        checkArgument(value != null, "unknown serialization format: ", uriText);
        return value;
    }

    /**
     * Finds the {@link SerializationFormat} with the specified {@link #uriText()}.
     */
    public static Optional<SerializationFormat> find(String uriText) {
        uriText = Ascii.toLowerCase(requireNonNull(uriText, "uriText"));
        return Optional.ofNullable(uriTextToFormats.get(uriText));
    }

    /**
     * Finds the {@link SerializationFormat} which is accepted by any of the specified media ranges.
     */
    public static Optional<SerializationFormat> find(MediaType... ranges) {
        requireNonNull(ranges, "ranges");
        if (ranges.length == 0) {
            return Optional.empty();
        }

        for (SerializationFormat f : values()) {
            if (f.isAccepted(ranges)) {
                return Optional.of(f);
            }
        }

        return Optional.empty();
    }

    /**
     * Finds the {@link SerializationFormat} which is accepted by the specified media range.
     *
     * @deprecated Use {@link #find(MediaType...)}.
     */
    @Deprecated
    public static Optional<SerializationFormat> fromMediaType(@Nullable String mediaType) {
        if (mediaType == null) {
            return Optional.empty();
        }

        try {
            return find(MediaType.parse(mediaType));
        } catch (IllegalArgumentException e) {
            // Malformed media type
            return Optional.empty();
        }
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
     * Returns whether any of the specified media ranges is accepted by any of the {@link #mediaTypes()}
     * defined by this format.
     */
    public boolean isAccepted(MediaType... ranges) {
        requireNonNull(ranges, "ranges");
        return mediaTypes.match(ranges).isPresent();
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public boolean equals(Object obj) {
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
