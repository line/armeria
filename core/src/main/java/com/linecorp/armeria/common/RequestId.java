/*
 * Copyright 2019 LINE Corporation
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

/**
 * An identifier of a {@link Request}-{@link Response} pair.
 * Note that this identifier does not guarantee uniqueness. A different {@link Request} may have the same
 * {@link RequestId}, although its chance would be fairly low.
 */
public interface RequestId {

    /**
     * Returns a {@link RequestId} created from a 64-bit random integer.
     */
    static RequestId random() {
        return new DefaultRequestId();
    }

    /**
     * Returns a {@link RequestId} created from the specified 64-bit integer.
     */
    static RequestId of(long value) {
        return new DefaultRequestId(value);
    }

    /**
     * Returns the full textual representation of this ID.
     */
    String text();

    /**
     * Returns the human-friendly short textual representation of this ID.
     */
    default String shortText() {
        final String longText = text();
        return longText.length() <= 8 ? longText : longText.substring(0, 8);
    }
}
