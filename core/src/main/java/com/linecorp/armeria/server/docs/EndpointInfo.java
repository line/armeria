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

package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;

import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Service;

/**
 * Metadata about the endpoints exposed by a {@link Service}.
 */
public final class EndpointInfo {

    private final String hostnamePattern;
    private final String path;

    @Nullable
    private final String regexPathPrefix;
    @Nullable
    private final String fragment;
    @Nullable
    private final MediaType defaultMimeType;
    private final Set<MediaType> availableMimeTypes;

    /**
     * Creates a new instance.
     */
    public EndpointInfo(String hostnamePattern, String path, @Nullable String regexPathPrefix,
                        @Nullable String fragment, @Nullable MediaType defaultMimeType,
                        Iterable<MediaType> availableMimeTypes) {

        this.hostnamePattern = requireNonNull(hostnamePattern, "hostnamePattern");
        this.path = requireNonNull(path, "path");
        this.regexPathPrefix = Strings.emptyToNull(regexPathPrefix);
        this.fragment = Strings.emptyToNull(fragment);
        this.defaultMimeType = defaultMimeType;

        this.availableMimeTypes = ImmutableSortedSet.copyOf(
                Comparator.comparing(MediaType::toString),
                requireNonNull(availableMimeTypes, "availableMimeTypes"));
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
     * Returns the prefix of this endpoint when the {@link #path()} returns regular expression string of
     * endpoint, otherwise {@code null}.
     */
    @JsonProperty
    @JsonInclude(Include.NON_NULL)
    @Nullable
    public String regexPathPrefix() {
        return regexPathPrefix;
    }

    /**
     * Returns the URI fragment of this endpoint.
     */
    @JsonProperty
    @JsonInclude(Include.NON_NULL)
    @Nullable
    public String fragment() {
        return fragment;
    }

    /**
     * Returns the default MIME type of this endpoint.
     */
    @JsonProperty
    @JsonInclude(Include.NON_NULL)
    @Nullable
    public MediaType defaultMimeType() {
        return defaultMimeType;
    }

    /**
     * Returns the set of available MIME types of this endpoint.
     */
    @JsonProperty
    public Set<MediaType> availableMimeTypes() {
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
               Objects.equals(regexPathPrefix, that.regexPathPrefix) &&
               Objects.equals(fragment, that.fragment) &&
               Objects.equals(defaultMimeType, that.defaultMimeType) &&
               availableMimeTypes.equals(that.availableMimeTypes);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("hostnamePattern", hostnamePattern)
                          .add("path", path)
                          .add("regexPathPrefix", regexPathPrefix)
                          .add("fragment", fragment)
                          .add("defaultMimeType", defaultMimeType)
                          .add("availableMimeTypes", availableMimeTypes)
                          .toString();
    }
}
