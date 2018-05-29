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
package com.linecorp.armeria.server.saml;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

/**
 * SAML name ID format and its option.
 */
public final class SamlNameIdPolicy {
    /**
     * Returns a {@link SamlNameIdPolicy} with the specified {@link SamlNameIdFormat} and
     * {@code isCreatable = true}.
     */
    public static SamlNameIdPolicy ofCreatable(SamlNameIdFormat format) {
        return of(format, true);
    }

    /**
     * Returns a {@link SamlNameIdPolicy} with the specified {@link SamlNameIdFormat} and
     * {@code isCreatable}.
     */
    public static SamlNameIdPolicy of(SamlNameIdFormat format, boolean isCreatable) {
        requireNonNull(format, "format");
        return new SamlNameIdPolicy(format, isCreatable);
    }

    private final SamlNameIdFormat format;
    private final boolean isCreatable;

    private SamlNameIdPolicy(SamlNameIdFormat format, boolean isCreatable) {
        this.format = format;
        this.isCreatable = isCreatable;
    }

    /**
     * Returns a {@link SamlNameIdFormat} of this {@link SamlNameIdPolicy}.
     */
    public SamlNameIdFormat format() {
        return format;
    }

    /**
     * Returns whether this {@link SamlNameIdPolicy} allows to create a new name ID.
     */
    public boolean isCreatable() {
        return isCreatable;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("format", format)
                          .add("isCreatable", isCreatable)
                          .toString();
    }
}
