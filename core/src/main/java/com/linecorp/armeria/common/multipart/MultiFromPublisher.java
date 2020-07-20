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
/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

/**
 * Implementation of {@link Multi} that is backed by a {@link Publisher}.
 *
 * @param <T> items type
 */
final class MultiFromPublisher<T> implements Multi<T> {

    // Forked from https://github.com/oracle/helidon/blob/8699b958c9441033a398dbb05d8ef23b2932a56d/common/reactive/src/main/java/io/helidon/common/reactive/MultiFromPublisher.java

    private final Publisher<? extends T> source;

    MultiFromPublisher(Publisher<? extends T> source) {
        requireNonNull(source, "source");
        this.source = source;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        source.subscribe(subscriber);
    }
}
