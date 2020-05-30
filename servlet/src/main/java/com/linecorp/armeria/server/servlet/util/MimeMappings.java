/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.server.servlet.util;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.linecorp.armeria.server.servlet.util.MimeMappings.Mapping;

/**
 * Simple container-independent abstraction for servlet mime mappings. Roughly equivalent
 * to the mime-mapping element traditionally found in web.xml.
 */
public class MimeMappings implements Iterable<Mapping> {

    // Forked from https://github.com/spring-projects/spring-boot/blob/master/spring-boot-project/
    // spring-boot/src/main/java/org/springframework/boot/web/server/MimeMappings.java

    private final Map<String, Mapping> map = new LinkedHashMap<String, Mapping>();

    @Override
    public Iterator<Mapping> iterator() {
        return getAll().iterator();
    }

    /**
     * Returns all defined mappings.
     * @return the mappings.
     */
    public Collection<Mapping> getAll() {
        return map.values();
    }

    /**
     * Add a new mime mapping.
     * @param extension the file extension (excluding '.')
     * @param mimeType the mime type to map
     * @return any previous mapping or {@code null}
     */
    @Nullable
    public String add(String extension, String mimeType) {
        requireNonNull(extension, "extension");
        requireNonNull(mimeType, "mimeType");
        final Mapping previous = map.put(extension, new Mapping(extension, mimeType));
        return (previous == null ? null : previous.getMimeType());
    }

    /**
     * Get a mime mapping for the given extension.
     * @param extension the file extension (excluding '.')
     * @return a mime mapping or {@code null}
     */
    @Nullable
    public String get(String extension) {
        requireNonNull(extension, "extension");
        final Mapping mapping = map.get(extension);
        return (mapping == null ? null : mapping.getMimeType());
    }

    /**
     * Remove an existing mapping.
     * @param extension the file extension (excluding '.')
     * @return the removed mime mapping or {@code null} if no item was removed
     */
    @Nullable
    public String remove(String extension) {
        requireNonNull(extension, "extension");
        final Mapping previous = map.remove(extension);
        return (previous == null ? null : previous.getMimeType());
    }

    @Override
    public int hashCode() {
        return map.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        if (obj instanceof MimeMappings) {
            final MimeMappings other = (MimeMappings) obj;
            return map.equals(other.map);
        }
        return false;
    }

    /**
     * A single mime mapping.
     */
    public final class Mapping {

        private final String extension;

        private final String mimeType;

        /**
         * Mapping.
         */
        public Mapping(String extension, String mimeType) {
            requireNonNull(extension, "extension");
            requireNonNull(mimeType, "mimeType");
            this.extension = extension;
            this.mimeType = mimeType;
        }

        /**
         * Get extension.
         */
        public String getExtension() {
            return extension;
        }

        /**
         * Get mime type.
         */
        public String getMimeType() {
            return mimeType;
        }

        @Override
        public int hashCode() {
            return extension.hashCode();
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj == null) {
                return false;
            }
            if (obj == this) {
                return true;
            }
            if (obj instanceof Mapping) {
                final Mapping other = (Mapping) obj;
                return extension.equals(other.extension) && mimeType.equals(other.mimeType);
            }
            return false;
        }

        @Override
        public String toString() {
            return "Mapping [extension=" + extension + ", mimeType=" + mimeType + "]";
        }
    }
}
