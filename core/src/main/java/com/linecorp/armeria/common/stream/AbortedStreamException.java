/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.Flags;

/**
 * A {@link RuntimeException} that is raised to signal a {@link Subscriber} that the {@link StreamMessage}
 * it subscribed to has been aborted by {@link StreamMessage#abort()}.
 */
public final class AbortedStreamException extends RuntimeException {

    private static final long serialVersionUID = -5271590540551141199L;

    static final AbortedStreamException INSTANCE = new AbortedStreamException(false);

    /**
     * Returns a newly-created {@link AbortedStreamException}.
     *
     * <p>This method used to return a singleton or a new instance, depending on
     * {@link Flags#verboseExceptionSampler()}'s decision. That's why the name of this method is {@code get}.
     * However, if there's a problem in certain circumstances, it is very hard to find the cause with
     * the singleton {@link AbortedStreamException}. So we have changed to return a newly-created instance.
     */
    public static AbortedStreamException get() {
        return new AbortedStreamException();
    }

    private AbortedStreamException() {}

    private AbortedStreamException(@SuppressWarnings("unused") boolean dummy) {
        super(null, null, false, false);
    }
}
