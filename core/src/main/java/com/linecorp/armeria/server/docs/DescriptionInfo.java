/*
 * Copyright 2022 LINE Corporation
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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.annotation.Description;

/**
 * Description of a type, a field, a method or a parameter.
 */
@UnstableApi
public final class DescriptionInfo {

    private static final DescriptionInfo EMPTY = new DescriptionInfo("", Markup.NONE);

    /**
     * Creates a new {@link DescriptionInfo} with the docStrings and specific markup.
     *
     * @param docString the documentation string of the field
     * @param markup the support markup of the field
     */
    public static DescriptionInfo of(String docString, Markup markup) {
        return new DescriptionInfo(docString, markup);
    }

    /**
     * Creates a new {@link DescriptionInfo} with the docStrings.
     *
     * @param docString the documentation string of the field
     */
    public static DescriptionInfo of(String docString) {
        return new DescriptionInfo(docString, Markup.NONE);
    }

    /**
     * Creates a new {@link DescriptionInfo} from the specified {@link Description}.
     */
    public static DescriptionInfo from(Description description) {
        requireNonNull(description, "description");
        return of(description.value(), description.markup());
    }

    /**
     * Returns an empty {@link DescriptionInfo}.
     */
    public static DescriptionInfo empty() {
        return EMPTY;
    }

    private final String docString;
    private final Markup markup;

    /**
     * Creates a new instance.
     * @param docString the documentation string
     * @param markup the supported markup string
     */
    private DescriptionInfo(String docString, Markup markup) {
        this.docString = requireNonNull(docString, "docString");
        this.markup = requireNonNull(markup, "markup");
    }

    /**
     * Returns the documentation string.
     */
    @JsonProperty
    public String docString() {
        return docString;
    }

    /**
     * Returns the supported markup.
     */
    @JsonProperty
    public Markup markup() {
        return markup;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DescriptionInfo)) {
            return false;
        }
        final DescriptionInfo that = (DescriptionInfo) o;
        return docString.equals(that.docString) && markup == that.markup;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(docString, markup);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("docString", docString)
                          .add("markup", markup)
                          .toString();
    }
}
