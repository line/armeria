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
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

/**
 * Implementation of {@link Multi} that is backed by a {@link StreamMessage}.
 *
 * @param <T> items type
 */
final class MultiFromStreamMessage<T> implements Multi<T> {

    private final StreamMessage<? extends T> source;
    private final SubscriptionOption[] options;

    MultiFromStreamMessage(StreamMessage<? extends T> source, SubscriptionOption... options) {
        requireNonNull(source, "source");
        this.source = source;
        this.options = options;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        source.subscribe(subscriber, options);
    }
}
