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

package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.FilteredStreamMessage;
import com.linecorp.armeria.unsafe.PooledObjects;

/**
 * An {@link HttpRequest} that filters objects as they are published. The filtering
 * will happen from an I/O thread, meaning the order of the filtering will match the
 * order that the {@code delegate} processes the objects in.
 */
public abstract class FilteredHttpRequest
        extends FilteredStreamMessage<HttpObject, HttpObject> implements HttpRequest {

    private final RequestHeaders headers;

    /**
     * Creates a new {@link FilteredHttpRequest} that filters objects published by {@code delegate}
     * before passing to a subscriber.
     */
    protected FilteredHttpRequest(HttpRequest delegate) {
        this(delegate, false);
    }

    /**
     * (Advanced users only) Creates a new {@link FilteredHttpRequest} that filters objects published by
     * {@code delegate} before passing to a subscriber.
     *
     * @param withPooledObjects if {@code true}, {@link FilteredStreamMessage#filter(Object)} receives the
     *                          pooled {@link HttpData} as is, without making a copy. If you don't know what
     *                          this means, use {@link #FilteredHttpRequest(HttpRequest)}.
     * @see PooledObjects
     */
    @UnstableApi
    protected FilteredHttpRequest(HttpRequest delegate, boolean withPooledObjects) {
        super(delegate, withPooledObjects);
        headers = delegate.headers();
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }
}
