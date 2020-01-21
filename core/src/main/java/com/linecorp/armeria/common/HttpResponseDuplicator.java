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
 * A duplicator that duplicates a {@link HttpResponse} into one or more {@link HttpResponse}s,
 * which publish the same elements.
 *
 * <pre>{@code
 * HttpResponse<String> httpResponse = ...
 * HttpResponseDuplicator<String> duplicator = httpResponse.toDuplicator();
 * // httpResponse.subscribe(...) will throw an exception. You cannot subscribe to httpResponse anymore.
 *
 * // Duplicate the stream as many as you want to subscribe.
 * HttpResponse<String> duplicatedHttpResponse1 = duplicator.duplicate();
 * HttpResponse<String> duplicatedHttpResponse2 = duplicator.duplicate();
 * duplicatedHttpResponse1.subscribe(...);
 * duplicatedHttpResponse2.subscribe(...);
 *
 * duplicator.close(); // You should call close to clean up the resources.
 * }</pre>
 *
 * <p>If you subscribe to the {@linkplain #duplicate() duplicated http response} with the
 * {@link SubscriptionOption#WITH_POOLED_OBJECTS}, the published elements can be shared across
 * {@link Subscriber}s. So do not manipulate the data unless you copy them.
 *
 * <p>To clean up the resources, you have to call one of {@linkplain #duplicate(boolean) duplicate(true)},
 * {@link #close()} or {@link #abort()}. Otherwise, memory leak might happen.</p>
 */

public interface HttpResponseDuplicator extends StreamMessageDuplicator<HttpObject> {

    /**
     * Returns a new {@link HttpResponse} that publishes the same elements with the {@link HttpResponse}
     * that this duplicator is created from.
     *
     * @param lastStream whether to prevent further duplication
     */
    @Override
    HttpResponse duplicate(boolean lastStream);

    /**
     * Returns a new {@link HttpResponse} that publishes the same elements with the {@link HttpResponse}
     * that this duplicator is created from.
     */
    @Override
    default HttpResponse duplicate() {
        return duplicate(false);
    }
}
