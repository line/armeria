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

package com.linecorp.armeria.server.annotation;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Description information used in Annotated Service when using {@link Description}.
 */
public class DescriptionInfo {
    final String docString;
    final Markup supportedMarkup;

    /**
     * Creates a new instance.
     * @param docString the documentation string
     * @param supportedMarkup the supported markup string
     */
    public DescriptionInfo(@Nullable String docString, Markup supportedMarkup) {
        this.docString = docString;
        this.supportedMarkup = requireNonNull(supportedMarkup, "supportedMarkup");
    }

    /**
     * Returns the documentation string.
     */
    public String getDocString() {
        return docString;
    }

    /**
     * Returns the supported markup.
     */
    public Markup getSupportedMarkup() {
        return supportedMarkup;
    }
}
