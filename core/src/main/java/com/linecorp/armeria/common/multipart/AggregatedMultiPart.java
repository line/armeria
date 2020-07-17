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
package com.linecorp.armeria.common.multipart;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

/**
 * A complete multipart whose body parts are readily available.
 */
public final class AggregatedMultiPart {

    private final String boundary;
    private final List<AggregatedBodyPart> bodyParts;

    AggregatedMultiPart(String boundary, List<AggregatedBodyPart> bodyParts) {
        this.boundary = boundary;
        this.bodyParts = bodyParts;
    }

    /**
     * Returns the boundary string.
     */
    public String boundary() {
        return boundary;
    }

    /**
     * Returns all the nested body parts.
     */
    public List<AggregatedBodyPart> bodyParts() {
        return bodyParts;
    }

    /**
     * Returns the first body part identified by the given control name. The control
     * name is the {@code name} parameter of the {@code Content-Disposition}
     * header for a body part with disposition type {@code form-data}.
     *
     * @param name control name
     * @return the {@link BodyPart} of the control name, or {@code null} if not present.
     */
    @Nullable
    public AggregatedBodyPart field(String name) {
        requireNonNull(name, "name");
        return bodyParts().stream()
                          .filter(part -> name.equals(part.name()))
                          .findFirst()
                          .orElse(null);
    }

    /**
     * Returns the body parts identified by the given control name. The control
     * name is the {@code name} parameter of the {@code Content-Disposition}
     * header for a body part with disposition type {@code form-data}.
     *
     * @param name control name
     */
    public List<AggregatedBodyPart> fields(String name) {
        requireNonNull(name, "name");
        return bodyParts().stream()
                          .filter(part -> name.equals(part.name()))
                          .collect(toImmutableList());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("boundary", boundary)
                          .add("bodyParts", bodyParts)
                          .toString();
    }
}
