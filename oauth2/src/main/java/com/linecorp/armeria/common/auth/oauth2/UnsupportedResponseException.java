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

import javax.annotation.Nullable;

/**
 * A response type in not supported by the given request/response flow.
 */
public class UnsupportedResponseException extends RuntimeException {

    private static final long serialVersionUID = 4982498806675787821L;

    private final int statusCode;

    /**
     * Constructs new {@link UnsupportedMediaTypeException}.
     * @param statusCode A status code of the response.
     * @param status An HTTP status of the response.
     * @param message A response content
     */
    public UnsupportedResponseException(int statusCode, String status, @Nullable String message) {
        super(join(status, message));
        this.statusCode = statusCode;
    }

    /**
     * Constructs new {@link UnsupportedMediaTypeException}.
     * @param statusCode A status code of the response.
     * @param status An HTTP status of the response.
     * @param message A response content
     * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method).
     *              (A {@code null} value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public UnsupportedResponseException(int statusCode, String status,
                                        @Nullable String message, @Nullable Throwable cause) {
        super(join(status, message), cause);
        this.statusCode = statusCode;
    }

    private static String join(String status, @Nullable String message) {
        return (message == null) ? status : status + ": " + message;
    }

    /**
     * A status code of the response.
     */
    public int getStatusCode() {
        return statusCode;
    }
}
