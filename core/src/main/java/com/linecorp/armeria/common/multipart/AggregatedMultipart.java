/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.common.multipart;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.linecorp.armeria.common.multipart.DefaultMultipart.randomBoundary;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A complete multipart whose body parts are readily available.
 */
public interface AggregatedMultipart {

    /**
     * Returns a new {@link AggregatedMultipart} with the default boundary.
     *
     * @param bodyParts the body part of the multipart message
     */
    static AggregatedMultipart of(AggregatedBodyPart... bodyParts) {
        return of(randomBoundary(), bodyParts);
    }

    /**
     * Returns a new {@link AggregatedMultipart} with the default boundary.
     *
     * @param bodyParts the body part of the multipart message
     */
    static AggregatedMultipart of(Iterable<? extends AggregatedBodyPart> bodyParts) {
        requireNonNull(bodyParts, "bodyParts");
        return of(randomBoundary(), bodyParts);
    }

    /**
     * Returns a new {@link AggregatedMultipart} with the specified {@code boundary}.
     *
     * @param boundary the boundary of the multipart message
     * @param bodyParts the body part of the multipart message
     */
    static AggregatedMultipart of(String boundary, AggregatedBodyPart... bodyParts) {
        return of(boundary, ImmutableList.copyOf(requireNonNull(bodyParts, "bodyParts")));
    }

    /**
     * Returns a new {@link AggregatedMultipart} with the specified {@code boundary}.
     *
     * @param boundary the boundary of the multipart message
     * @param bodyParts the body part of the multipart message
     */
    static AggregatedMultipart of(String boundary, Iterable<? extends AggregatedBodyPart> bodyParts) {
        requireNonNull(boundary, "boundary");
        requireNonNull(bodyParts, "bodyParts");

        return new DefaultAggregatedMultipart(boundary, ImmutableList.copyOf(bodyParts));
    }

    /**
     * Returns the boundary string.
     */
    String boundary();

    /**
     * Returns all the nested body parts.
     */
    List<AggregatedBodyPart> bodyParts();

    /**
     * Returns the first body part identified by the given control name. The control
     * name is the {@code name} parameter of the {@code "content-disposition"}
     * header for a body part with disposition type {@code form-data}.
     *
     * @param name control name
     * @return the {@link BodyPart} of the control name, or {@code null} if not present.
     */
    @Nullable
    default AggregatedBodyPart field(String name) {
        requireNonNull(name, "name");
        return bodyParts().stream()
                          .filter(part -> name.equals(part.name()))
                          .findFirst()
                          .orElse(null);
    }

    /**
     * Returns the body parts identified by the given control name. The control
     * name is the {@code name} parameter of the {@code "content-disposition"}
     * header for a body part with disposition type {@code form-data}.
     *
     * @param name control name
     */
    default List<AggregatedBodyPart> fields(String name) {
        requireNonNull(name, "name");
        return bodyParts().stream()
                          .filter(part -> name.equals(part.name()))
                          .collect(toImmutableList());
    }

    /**
     * Returns the all control names of the body parts. The control
     * name is the {@code name} parameter of the {@code "content-disposition"}
     * header for a body part with disposition type {@code form-data}.
     */
    default Set<String> names() {
        return bodyParts().stream()
                          .map(AggregatedBodyPart::name)
                          .collect(toImmutableSet());
    }
}
