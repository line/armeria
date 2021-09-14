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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.Service;

/**
 * Metadata about the endpoints exposed by a {@link Service}.
 */
@UnstableApi
@JsonInclude(Include.NON_NULL)
public final class EndpointInfo {

    /**
     * Returns a newly created {@link EndpointInfoBuilder} that builds the {@link EndpointInfo} with
     * the specified {@code hostnamePattern} and {@code pathMapping}.
     */
    public static EndpointInfoBuilder builder(String hostnamePattern, String pathMapping) {
        return new EndpointInfoBuilder(hostnamePattern, pathMapping);
    }

    private final String hostnamePattern;
    private final String pathMapping;

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
    EndpointInfo(String hostnamePattern, String pathMapping, @Nullable String regexPathPrefix,
                 @Nullable String fragment, @Nullable MediaType defaultMimeType,
                 Iterable<MediaType> availableMimeTypes) {

        this.hostnamePattern = requireNonNull(hostnamePattern, "hostnamePattern");
        this.pathMapping = requireNonNull(pathMapping, "pathMapping");
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
     * Returns the path mapping of this endpoint.
     */
    @JsonProperty
    public String pathMapping() {
        return pathMapping;
    }

    /**
     * Returns the prefix of this endpoint if the {@link #pathMapping()} returns a regular expression string
     * of endpoint and the prefix exists, otherwise {@code null}.
     */
    @JsonProperty
    @Nullable
    public String regexPathPrefix() {
        return regexPathPrefix;
    }

    /**
     * Returns the URI fragment of this endpoint.
     */
    @JsonProperty
    @Nullable
    public String fragment() {
        return fragment;
    }

    /**
     * Returns the default MIME type of this endpoint.
     */
    @JsonProperty
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
        return Objects.hash(hostnamePattern, pathMapping, regexPathPrefix, fragment,
                            defaultMimeType, availableMimeTypes);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof EndpointInfo)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        final EndpointInfo that = (EndpointInfo) obj;
        return hostnamePattern.equals(that.hostnamePattern) &&
               pathMapping.equals(that.pathMapping) &&
               Objects.equals(regexPathPrefix, that.regexPathPrefix) &&
               Objects.equals(fragment, that.fragment) &&
               Objects.equals(defaultMimeType, that.defaultMimeType) &&
               availableMimeTypes.equals(that.availableMimeTypes);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("hostnamePattern", hostnamePattern)
                          .add("pathMapping", pathMapping)
                          .add("regexPathPrefix", regexPathPrefix)
                          .add("fragment", fragment)
                          .add("defaultMimeType", defaultMimeType)
                          .add("availableMimeTypes", availableMimeTypes)
                          .toString();
    }
}
