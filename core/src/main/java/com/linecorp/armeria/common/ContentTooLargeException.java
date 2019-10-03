/*
 * Copyright 2016 LINE Corporation
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
 * A {@link RuntimeException} raised when the length of request or response content exceeds its limit.
 */
public final class ContentTooLargeException extends RuntimeException {

    private static final long serialVersionUID = 4901614315474105954L;

    private static final ContentTooLargeException INSTANCE = new ContentTooLargeException(false);

    /**
     * Returns a {@link ContentTooLargeException} which may be a singleton or a new instance, depending on
     * {@link Flags#verboseExceptionSampler()}'s decision.
     */
    public static ContentTooLargeException get() {
        return Flags.verboseExceptionSampler().isSampled(ContentTooLargeException.class) ?
               new ContentTooLargeException() : INSTANCE;
    }

    private ContentTooLargeException() {}

    private ContentTooLargeException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
