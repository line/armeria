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

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Represent an entity of an HTTP message.
 */
@UnstableApi
@Nullable
public interface HttpEntity<T> {

    /**
     * Returns the {@link HttpHeaders} of this entity.
     */
    HttpHeaders headers();

    /**
     * Returns the content of this entity.
     *
     * @throws NoContentException if the content is null.
     */
    @Nullable
    T content();

    /**
     * Returns true if the {@link #content()} is null.
     */
    boolean hasContent();

    /**
     * Returns the {@linkplain  HttpHeaders trailers} of this entity.
     */
    HttpHeaders trailers();
}
