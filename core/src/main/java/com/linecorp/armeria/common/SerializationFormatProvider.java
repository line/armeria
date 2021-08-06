/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.base.Ascii;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Registers the {@link SerializationFormat}s dynamically via Java SPI (Service Provider Interface).
 */
@UnstableApi
public abstract class SerializationFormatProvider {

    /**
     * Returns the {@link Entry}s to register as {@link SerializationFormat}s.
     */
    protected abstract Set<Entry> entries();

    /**
     * A registration entry of a {@link SerializationFormat}.
     */
    @UnstableApi
    protected static final class Entry implements Comparable<Entry> {
        final String uriText;
        final MediaType primaryMediaType;
        final MediaTypeSet mediaTypes;

        /**
         * Creates a new instance.
         */
        public Entry(String uriText, MediaType primaryMediaType, MediaType... alternativeMediaTypes) {
            this.uriText = Ascii.toLowerCase(requireNonNull(uriText, "uriText"));
            this.primaryMediaType = requireNonNull(primaryMediaType, "primaryMediaType");
            requireNonNull(alternativeMediaTypes, "alternativeMediaTypes");
            mediaTypes = MediaTypeSet.of(ImmutableList.<MediaType>builder()
                                                 .add(primaryMediaType)
                                                 .add(alternativeMediaTypes)
                                                 .build());
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }

            if (obj == null || obj.getClass() != Entry.class) {
                return false;
            }

            return uriText.equals(((Entry) obj).uriText);
        }

        @Override
        public int compareTo(Entry o) {
            return uriText.compareTo(o.uriText);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("uriText", uriText)
                              .add("mediaTypes", mediaTypes).toString();
        }

        @Override
        public int hashCode() {
            return uriText.hashCode();
        }
    }
}
