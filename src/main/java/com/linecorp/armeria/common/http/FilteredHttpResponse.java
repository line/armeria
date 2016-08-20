/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.http;

import com.linecorp.armeria.common.stream.FilteredStreamMessage;

/**
 * An {@link HttpResponse} that filters objects as they are published. The filtering
 * will happen from an I/O thread, meaning the order of the filtering will match the
 * order that the {@code delegate} processes the objects in.
 */
public abstract class FilteredHttpResponse
        extends FilteredStreamMessage<HttpObject, HttpObject> implements HttpResponse {

    /**
     * Creates a new {@link FilteredHttpResponse} that filters objects published by {@code delegate}
     * before passing to a subscriber.
     */
    protected FilteredHttpResponse(HttpResponse delegate) {
        super(delegate);
    }
}
