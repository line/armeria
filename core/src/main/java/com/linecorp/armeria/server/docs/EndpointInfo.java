/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import static com.google.common.collect.ImmutableSortedSet.toImmutableSortedSet;
import static java.util.Objects.requireNonNull;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.Service;

/**
 * Metadata about the endpoints exposed by a {@link Service}.
 */
public final class EndpointInfo {

    private final String hostnamePattern;
    private final String path;
    private final String fragment;
    private final String defaultMimeType;
    private final Set<String> availableMimeTypes;

    /**
     * Creates a new instance.
     */
    public EndpointInfo(String hostnamePattern, String path, String fragment,
                        SerializationFormat defaultFormat, Iterable<SerializationFormat> availableFormats) {

        this.hostnamePattern = requireNonNull(hostnamePattern, "hostnamePattern");
        this.path = requireNonNull(path, "path");
        this.fragment = requireNonNull(fragment, "fragment");
        defaultMimeType = requireNonNull(defaultFormat, "defaultFormat").mediaType().toString();

        availableMimeTypes = Streams.stream(availableFormats)
                                    .map(SerializationFormat::mediaType)
                                    .map(Object::toString)
                                    .collect(toImmutableSortedSet(Comparator.naturalOrder()));
    }

    /**
     * Returns the hostname pattern of this endpoint.
     */
    @JsonProperty
    public String hostnamePattern() {
        return hostnamePattern;
    }

    /**
     * Returns the path of this endpoint.
     */
    @JsonProperty
    public String path() {
        return path;
    }

    /**
     * Returns the URI fragment of this endpoint.
     */
    @JsonProperty
    public String fragment() {
        return fragment;
    }

    /**
     * Returns the default MIME type of this endpoint.
     */
    @JsonProperty
    public String defaultMimeType() {
        return defaultMimeType;
    }

    /**
     * Returns the set of available MIME types of this endpoint.
     */
    @JsonProperty
    public Set<String> availableMimeTypes() {
        return availableMimeTypes;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hostnamePattern, path);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof EndpointInfo)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        final EndpointInfo that = (EndpointInfo) obj;
        return hostnamePattern.equals(that.hostnamePattern) &&
               path.equals(that.path) &&
               fragment.equals(that.fragment) &&
               defaultMimeType.equals(that.defaultMimeType) &&
               availableMimeTypes.equals(that.availableMimeTypes);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("hostnamePattern", hostnamePattern)
                          .add("path", path)
                          .add("fragment", fragment)
                          .add("defaultMimeType", defaultMimeType)
                          .add("availableMimeTypes", availableMimeTypes)
                          .toString();
    }
}
