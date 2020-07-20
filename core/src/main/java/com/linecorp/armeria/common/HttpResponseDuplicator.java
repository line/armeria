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
 * A duplicator that duplicates a {@link HttpResponse} into one or more {@link HttpResponse}s,
 * which publish the same elements.
 *
 * <pre>{@code
 * HttpResponse res = ...
 * try (HttpResponseDuplicator duplicator = res.toDuplicator()) {
 *     // res.subscribe(...) will throw an exception. You cannot subscribe to res anymore.
 *
 *     // Duplicate the response as many as you want to subscribe.
 *     HttpResponse duplicatedResponse1 = duplicator.duplicate();
 *     HttpResponse duplicatedResponse2 = duplicator.duplicate();
 *
 *     duplicatedResponse1.subscribe(...);
 *     duplicatedResponse2.subscribe(...);
 * }
 * }</pre>
 *
 * <p>Use the {@code try-with-resources} block or call {@link #close()} manually to clean up the resources
 * after all subscriptions are done. If you want to stop publishing and clean up the resources immediately,
 * call {@link #abort()}. If you do none of these, memory leak might happen.</p>
 *
 * <p>If you subscribe to the {@linkplain #duplicate() duplicated http response} with the
 * {@link SubscriptionOption#WITH_POOLED_OBJECTS}, the published elements can be shared across
 * {@link Subscriber}s. So do not manipulate the data unless you copy them.
 */

public interface HttpResponseDuplicator extends StreamMessageDuplicator<HttpObject> {

    /**
     * Returns a new {@link HttpResponse} that publishes the same {@link ResponseHeaders}, {@link HttpData}s
     * and {@linkplain HttpHeaders trailers} as the {@link HttpResponse} that this duplicator is created from.
     */
    @Override
    @CheckReturnValue
    HttpResponse duplicate();
}
