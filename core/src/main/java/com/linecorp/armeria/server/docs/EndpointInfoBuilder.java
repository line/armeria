/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Objects.requireNonNull;

import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SerializationFormat;

/**
 * Creates a new {@link EndpointInfo} using the builder pattern.
 */
public final class EndpointInfoBuilder {

    private final String hostnamePattern;
    private final String path;

    @Nullable
    private String regexPathPrefix;
    @Nullable
    private String fragment;
    @Nullable
    private MediaType defaultMimeType;
    @Nullable
    private Set<MediaType> availableMimeTypes;

    /**
     * Creates a new {@link EndpointInfoBuilder} that builds the {@link EndpointInfo} with the specified
     * {@code hostnamePattern} and {@code path}.
     */
    public EndpointInfoBuilder(String hostnamePattern, String path) {
        this.hostnamePattern = requireNonNull(hostnamePattern, "hostnamePattern");
        this.path = requireNonNull(path, "path");
    }

    /**
     * Sets the prefix of the path when the path is regular expression string or prefix matching.
     */
    public EndpointInfoBuilder regexPathPrefix(String regexPathPrefix) {
        requireNonNull(regexPathPrefix, "regexPathPrefix");
        checkState(fragment == null, "regexPathPrefix cannot be set with fragment: %s", fragment);
        this.regexPathPrefix = regexPathPrefix;
        return this;
    }

    /**
     * Sets the fragment of the path.
     */
    public EndpointInfoBuilder fragment(String fragment) {
        requireNonNull(fragment, "fragment");
        checkState(regexPathPrefix == null, "fragment cannot be set with regexPathPrefix: %s", regexPathPrefix);
        this.fragment = fragment;
        return this;
    }

    /**
     * Sets the default {@link SerializationFormat}.
     */
    public EndpointInfoBuilder defaultFormat(SerializationFormat defaultFormat) {
        requireNonNull(defaultFormat, "defaultFormat");
        return defaultMimeType(defaultFormat.mediaType());
    }

    /**
     * Sets the default {@link MediaType}.
     */
    public EndpointInfoBuilder defaultMimeType(MediaType defaultMimeType) {
        requireNonNull(defaultMimeType, "defaultMimeType");
        this.defaultMimeType = defaultMimeType;

        if (availableMimeTypes == null) {
            this.availableMimeTypes = ImmutableSet.of(defaultMimeType);
        } else if (!availableMimeTypes.contains(defaultMimeType)) {
            availableMimeTypes = addDefaultMimeType(defaultMimeType, availableMimeTypes);
        }
        return this;
    }

    private static Set<MediaType> addDefaultMimeType(MediaType defaultMimeType,
                                                     Iterable<MediaType> availableMimeTypes) {
        return ImmutableSet.<MediaType>builder().add(defaultMimeType).addAll(availableMimeTypes).build();
    }

    /**
     * Sets the available {@link SerializationFormat}s.
     */
    public EndpointInfoBuilder availableFormats(SerializationFormat... availableFormats) {
        requireNonNull(availableFormats, "availableFormats");
        return availableFormats(ImmutableSet.copyOf(availableFormats));
    }

    /**
     * Sets the available {@link SerializationFormat}s.
     */
    public EndpointInfoBuilder availableFormats(Iterable<SerializationFormat> availableFormats) {
        requireNonNull(availableFormats, "availableFormats");
        return availableMimeTypes(Streams.stream(availableFormats).map(SerializationFormat::mediaType)
                                         .collect(toImmutableSet()));
    }

    /**
     * Sets the available {@link MediaType}s.
     */
    public EndpointInfoBuilder availableMimeTypes(MediaType... availableMimeTypes) {
        requireNonNull(availableMimeTypes, "availableMimeTypes");
        return availableMimeTypes(ImmutableSet.copyOf(availableMimeTypes));
    }

    /**
     * Sets the available {@link MediaType}s.
     */
    public EndpointInfoBuilder availableMimeTypes(Iterable<MediaType> availableMimeTypes) {
        requireNonNull(availableMimeTypes, "availableMimeTypes");
        checkArgument(Iterables.size(availableMimeTypes) > 0, "Should at least have a available media type.");
        if (defaultMimeType != null && !Iterables.contains(availableMimeTypes, defaultMimeType)) {
            this.availableMimeTypes = addDefaultMimeType(defaultMimeType, availableMimeTypes);
        } else {
            this.availableMimeTypes = ImmutableSet.copyOf(availableMimeTypes);
        }
        return this;
    }

    /**
     * Returns a newly-created {@link EndpointInfo} based on the properties of this builder.
     */
    public EndpointInfo build() {
        checkState(availableMimeTypes != null && Iterables.size(availableMimeTypes) > 0,
                   "Should at least have one available media type.");
        return new EndpointInfo(hostnamePattern, path, regexPathPrefix,
                                fragment, defaultMimeType, availableMimeTypes);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("hostnamePattern", hostnamePattern)
                          .add("regexPathPrefix", regexPathPrefix)
                          .add("fragment", fragment)
                          .add("defaultMimeType", defaultMimeType)
                          .add("availableMimeTypes", availableMimeTypes)
                          .toString();
    }
}
