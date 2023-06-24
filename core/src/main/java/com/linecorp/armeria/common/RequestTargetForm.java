/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * {@link RequestTarget} form, as defined in
 * <a href="https://datatracker.ietf.org/doc/html/rfc9112#section-3.2">Section 3.2, RFC 9112</a>.
 *
 * <p>Note: This enum doesn't support the
 * <a href="https://datatracker.ietf.org/doc/html/rfc9112#section-3.2.3">authority form</a>.
 */
@UnstableApi
public enum RequestTargetForm {
    /**
     * An absolute path followed by a query and a fragment.
     */
    ORIGIN,
    /**
     * An absolute URI that has scheme, authority and absolute path followed by a query and a fragment.
     */
    ABSOLUTE,
    /**
     * {@code "*"}, used for a server-side {@code OPTIONS} request.
     */
    ASTERISK
}
