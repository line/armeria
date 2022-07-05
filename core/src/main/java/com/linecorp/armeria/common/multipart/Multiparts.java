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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.linecorp.armeria.common.MediaType;

/**
 * Utility methods to support multipart metadata handling.
 */
public final class Multiparts {

    /**
     * Extracts {@code boundary} parameter value from the multipart {@link MediaType}.
     * @param contentType {@link MediaType} that represents on of the multipart subtypes.
     * @return {@code boundary} parameter value extracted from the multipart {@link MediaType}.
     * @throws IllegalArgumentException if the specified {@link MediaType} is not multipart
     * @throws IllegalStateException if {@code boundary} parameter is missing on the specified {@link MediaType}
     */
    public static String getBoundary(MediaType contentType) {
        requireNonNull(contentType, "contentType");
        checkArgument(contentType.isMultipart(),
                      "Content-Type: %s (expected: multipart content type)", contentType);
        final List<String> boundary = contentType.parameters().get("boundary");

        if (boundary == null || boundary.isEmpty()) {
            throw new IllegalStateException("boundary parameter is missing on the Content-Type header");
        }
        return boundary.get(0);
    }

    private Multiparts() {}
}
