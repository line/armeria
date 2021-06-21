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

import javax.annotation.Nullable;

import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.MediaType;

/**
 * Utility methods to support multipart metadata handling.
 */
public final class Multiparts {

    /**
     * Checks whether provided {@link MediaType} is a multipart type.
     * @param contentType {@link MediaType} to check against or {@code null} if undefined.
     * @return true, only if provided {@link MediaType} is a multipart type.
     */
    static boolean isMultipart(@Nullable MediaType contentType) {
        return contentType != null && MediaType.ANY_MULTIPART_TYPE.type().equals(contentType.type());
    }

    /**
     * Extracts {@code boundary} parameter value from the multipart {@link MediaType}.
     * @param contentType {@link MediaType} that represents on of the multipart subtypes.
     * @return {@code boundary} parameter value extracted from the multipart {@link MediaType}.
     */
    static String getBoundary(MediaType contentType) {
        requireNonNull(contentType, "contentType");
        checkArgument(isMultipart(contentType),
                      "Content-Type: %s (expected: multipart content type)", contentType);
        @Nullable
        final String boundary = Iterables.getFirst(contentType.parameters().get("boundary"), null);
        if (boundary == null) {
            throw new IllegalStateException("boundary parameter is missing on the Content-Type header");
        }
        return boundary;
    }

    private Multiparts() {}
}
