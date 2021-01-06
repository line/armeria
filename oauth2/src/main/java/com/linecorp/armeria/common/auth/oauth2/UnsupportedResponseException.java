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

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A response type in not supported by the given request/response flow.
 */
@UnstableApi
public class UnsupportedResponseException extends RuntimeException {

    private static final long serialVersionUID = 4982498806675787821L;

    private final HttpStatus status;

    /**
     * Constructs new {@link UnsupportedMediaTypeException}.
     * @param status An {@link HttpStatus} of the response.
     * @param message A response content
     */
    public UnsupportedResponseException(HttpStatus status, @Nullable String message) {
        super(join(status.toString(), message));
        this.status = status;
    }

    /**
     * Constructs new {@link UnsupportedMediaTypeException}.
     * @param status An {@link HttpStatus} of the response.
     * @param message A response content
     * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method).
     *              (A {@code null} value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public UnsupportedResponseException(HttpStatus status, @Nullable String message,
                                        @Nullable Throwable cause) {
        super(join(status.toString(), message), cause);
        this.status = status;
    }

    private static String join(String status, @Nullable String message) {
        return (message == null) ? status : status + ": " + message;
    }

    /**
     * A status code of the response.
     */
    public HttpStatus getStatus() {
        return status;
    }
}
