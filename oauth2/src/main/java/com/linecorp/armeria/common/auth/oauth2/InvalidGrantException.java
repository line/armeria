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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * The provided authorization grant (e.g., authorization code, resource owner credentials) or
 * refresh token is invalid, expired, revoked, does not match the redirection URI used in the
 * authorization request, or was issued to another client.
 */
@UnstableApi
public final class InvalidGrantException extends TokenRequestException {

    private static final long serialVersionUID = -7174852945693093094L;

    /**
     * Constructs a new {@link InvalidGrantException} using {@code errorDescription} and {@code errorUri}.
     * @param errorDescription OPTIONAL. Human-readable ASCII [USASCII] text providing additional information,
     *                         used to assist the client developer in understanding the error that occurred.
     *                         Values for the {@code errorDescription} parameter MUST NOT include
     *                         characters outside the set {@code %x20-21} / {@code %x23-5B} / {@code %x5D-7E}.
     * @param errorUri OPTIONAL. A URI identifying a human-readable web page with information about the error,
     *                 used to provide the client developer with additional information about the error.
     *                 Values for the {@code errorUri} parameter MUST conform to the URI-reference syntax and
     *                 thus MUST NOT include characters outside
     *                 the set {@code %x21} / {@code %x23-5B} / {@code %x5D-7E}.
     */
    public InvalidGrantException(String errorDescription, String errorUri) {
        super(errorDescription, errorUri);
    }

    /**
     * Constructs a new {@link InvalidGrantException} using {@code errorDescription} and {@code errorUri}.
     * @param errorDescription OPTIONAL. Human-readable ASCII [USASCII] text providing additional information,
     *                         used to assist the client developer in understanding the error that occurred.
     *                         Values for the {@code errorDescription} parameter MUST NOT include
     *                         characters outside the set {@code %x20-21} / {@code %x23-5B} / {@code %x5D-7E}.
     * @param errorUri OPTIONAL. A URI identifying a human-readable web page with information about the error,
     *                 used to provide the client developer with additional information about the error.
     *                 Values for the {@code errorUri} parameter MUST conform to the URI-reference syntax and
     *                 thus MUST NOT include characters outside
     *                 the set {@code %x21} / {@code %x23-5B} / {@code %x5D-7E}.
     * @param cause the cause (which is saved for later retrieval by the {@link #getCause()} method).
     *              (A {@code null} value is permitted, and indicates that the cause is nonexistent or unknown.)
     */
    public InvalidGrantException(String errorDescription, String errorUri, Throwable cause) {
        super(errorDescription, errorUri, cause);
    }
}
