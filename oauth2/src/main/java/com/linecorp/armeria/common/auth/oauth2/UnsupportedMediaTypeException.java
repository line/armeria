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

package com.linecorp.armeria.common.auth.oauth2;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A response {@code Content-Type} header does not match the expected type.
 */
@UnstableApi
public final class UnsupportedMediaTypeException extends InvalidResponseException {

    private static final long serialVersionUID = 5350546517834409748L;

    private final String mediaType;

    /**
     * Constructs new {@link UnsupportedMediaTypeException}.
     * @param mediaType A {@code Content-Type} of the response.
     * @param status An HTTP status of the response.
     * @param message A response content
     */
    public UnsupportedMediaTypeException(String mediaType, String status, @Nullable String message) {
        super(status, join(mediaType, message));
        this.mediaType = mediaType;
    }

    /**
     * Constructs new {@link UnsupportedMediaTypeException}.
     * @param mediaType A {@code Content-Type} of the response.
     * @param status An HTTP status of the response.
     * @param message A response content
     * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method).
     *              (A {@code null} value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public UnsupportedMediaTypeException(String mediaType, String status, @Nullable String message,
                                         @Nullable Throwable cause) {
        super(status, join(mediaType, message), cause);
        this.mediaType = mediaType;
    }

    private static String join(String mediaType, @Nullable String message) {
        final StringBuilder builder = new StringBuilder();
        builder.append(HttpHeaderNames.CONTENT_TYPE).append(" - ")
               .append(mediaType);
        return (message == null) ? builder.toString() : builder.append(": ").append(message).toString();
    }

    /**
     * A {@code Content-Type} of the response.
     */
    public String getMediaType() {
        return mediaType;
    }
}
