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

import com.linecorp.armeria.common.stream.StreamMessageDuplicator;
import com.linecorp.armeria.common.stream.SubscriptionOption;

/**
 * A duplicator that duplicates a {@link HttpRequest} into one or more {@link HttpRequest}s,
 * which publish the same elements.
 *
 * <pre>{@code
 * HttpRequest httpRequest = ...
 * HttpRequestDuplicator duplicator = httpRequest.toDuplicator();
 * // httpRequest.subscribe(...) will throw an exception. You cannot subscribe to httpRequest anymore.
 *
 * // Duplicate the stream as many as you want to subscribe.
 * HttpRequest duplicatedHttpRequest1 = duplicator.duplicate();
 * HttpRequest duplicatedHttpRequest2 = duplicator.duplicate();
 * duplicator.close(); // You should call close if you don't want to duplicate the requests anymore
 *                     // so that the resources are cleaned up after all subscriptions are done.
 *
 * // duplicator.duplicate(); will throw an exception. You cannot duplicate it anymore.
 *
 * duplicatedHttpRequest1.subscribe(...);
 * duplicatedHttpRequest2.subscribe(...);
 * }</pre>
 *
 * <p>If you subscribe to the {@linkplain #duplicate() duplicated http request} with the
 * {@link SubscriptionOption#WITH_POOLED_OBJECTS}, the published elements can be shared across
 * {@link Subscriber}s. So do not manipulate the data unless you copy them.
 *
 * <p>To clean up the resources, you have to call {@link #close()} or {@link #abort()}.
 * Otherwise, memory leak might happen.</p>
 */
public interface HttpRequestDuplicator extends StreamMessageDuplicator<HttpObject> {

    /**
     * Returns a new {@link HttpRequest} that publishes the same elements with the {@link HttpRequest}
     * that this duplicator is created from.
     */
    @Override
    HttpRequest duplicate();

    /**
     * Returns a new {@link HttpRequest} with the specified {@link RequestHeaders} that publishes the same
     * elements with the {@link HttpRequest} that this duplicator is created from.
     */
    HttpRequest duplicate(RequestHeaders newHeaders);
}
