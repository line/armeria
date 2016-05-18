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

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.common.SerializationFormat;

class EndpointInfo {

    static EndpointInfo of(String hostnamePattern, String path,
                           SerializationFormat defaultFormat, Set<SerializationFormat> formats) {
        return new EndpointInfo(hostnamePattern, path, defaultFormat, formats);
    }

    private final String hostnamePattern;
    private final String path;
    private final String defaultMimeType;
    private final Set<String> availableMimeTypes;

    EndpointInfo(String hostnamePattern, String path,
                 SerializationFormat defaultFormat, Set<SerializationFormat> availableFormats) {
        this.hostnamePattern = requireNonNull(hostnamePattern, "hostnamePattern");
        this.path = requireNonNull(path, "path");
        defaultMimeType = requireNonNull(defaultFormat, "defaultFormat").mediaType().toString();

        final Set<String> sortedAvailableMimeTypes =
                availableFormats.stream()
                                .map(SerializationFormat::mediaType)
                                .map(Object::toString)
                                .collect(Collectors.toCollection(TreeSet::new));
        availableMimeTypes = Collections.unmodifiableSet(sortedAvailableMimeTypes);
    }

    @JsonProperty
    String hostnamePattern() {
        return hostnamePattern;
    }

    @JsonProperty
    String path() {
        return path;
    }

    @JsonProperty
    String defaultMimeType() {
        return defaultMimeType;
    }

    @JsonProperty
    Set<String> availableMimeTypes() {
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
               defaultMimeType.equals(that.defaultMimeType) &&
               availableMimeTypes.equals(that.availableMimeTypes);
    }

    @Override
    public String toString() {
        return "EndpointInfo{" +
               "hostnamePattern=" + hostnamePattern +
               ", path=" + path +
               ", defaultMimeType=" + defaultMimeType +
               ", availableMimeTypes=" + availableMimeTypes +
               '}';
    }
}
