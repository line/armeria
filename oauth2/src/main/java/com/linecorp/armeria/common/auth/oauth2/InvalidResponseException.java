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

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An HTTP response that is not valid for the given request/response flow.
 */
@UnstableApi
public class InvalidResponseException extends RuntimeException {

    private static final long serialVersionUID = -6985569386139579552L;

    private final String status;

    /**
     * Constructs new {@link InvalidResponseException}.
     * @param status An {@link HttpStatus} of the response.
     * @param message A response content
     */
    public InvalidResponseException(HttpStatus status, @Nullable String message) {
        this(status.toString(), message);
    }

    /**
     * Constructs new {@link InvalidResponseException}.
     * @param status An {@link HttpStatus} of the response.
     * @param message A response content
     * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method).
     *              (A {@code null} value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public InvalidResponseException(HttpStatus status, @Nullable String message, @Nullable Throwable cause) {
        this(status.toString(), message, cause);
    }

    /**
     * Constructs new {@link InvalidResponseException}.
     * @param status An HTTP status of the response.
     * @param message A response content
     */
    public InvalidResponseException(String status, @Nullable String message) {
        super(join(status, message));
        this.status = status;
    }

    /**
     * Constructs new {@link InvalidResponseException}.
     * @param status An HTTP status of the response.
     * @param message A response content
     * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method).
     *              (A {@code null} value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public InvalidResponseException(String status, @Nullable String message, @Nullable Throwable cause) {
        super(join(status, message), cause);
        this.status = status;
    }

    /**
     * A status code of the response.
     */
    public String getStatus() {
        return status;
    }

    private static String join(String status, @Nullable String message) {
        return (message == null) ? status : status + ": " + message;
    }
}
