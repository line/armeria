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

import org.reactivestreams.Subscriber;

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.stream.StreamMessageDuplicator;
import com.linecorp.armeria.common.stream.SubscriptionOption;

/**
 * A duplicator that duplicates a {@link HttpRequest} into one or more {@link HttpRequest}s,
 * which publish the same elements.
 *
 * <pre>{@code
 * HttpRequest req = ...
 * try (HttpRequestDuplicator duplicator = req.toDuplicator()) {
 *     // req.subscribe(...) will throw an exception. You cannot subscribe to req anymore.
 *
 *     // Duplicate the request as many as you want to subscribe.
 *     HttpRequest duplicatedRequest = duplicator.duplicate();
 *     HttpRequest duplicatedRequest = duplicator.duplicate();
 *
 *     duplicatedRequest.subscribe(...);
 *     duplicatedRequest.subscribe(...);
 * }
 * }</pre>
 *
 * <p>Use the {@code try-with-resources} block or call {@link #close()} manually to clean up the resources
 * after all subscriptions are done. If you want to stop publishing and clean up the resources immediately,
 * call {@link #abort()}. If you do none of these, memory leak might happen.</p>
 *
 * <p>If you subscribe to the {@linkplain #duplicate() duplicated http request} with the
 * {@link SubscriptionOption#WITH_POOLED_OBJECTS}, the published elements can be shared across
 * {@link Subscriber}s. So do not manipulate the data unless you copy them.
 */
public interface HttpRequestDuplicator extends StreamMessageDuplicator<HttpObject> {

    /**
     * Returns the {@link RequestHeaders}.
     */
    RequestHeaders headers();

    /**
     * Returns a new {@link HttpRequest} that publishes the same {@link HttpData}s and
     * {@linkplain HttpHeaders trailers} as the {@link HttpRequest} that this duplicator is created from.
     */
    @Override
    @CheckReturnValue
    HttpRequest duplicate();

    /**
     * Returns a new {@link HttpRequest} with the specified {@link RequestHeaders} that publishes the same
     * {@link HttpData}s and {@linkplain HttpHeaders trailers} as the {@link HttpRequest} that
     * this duplicator is created from.
     */
    @CheckReturnValue
    HttpRequest duplicate(RequestHeaders newHeaders);
}
