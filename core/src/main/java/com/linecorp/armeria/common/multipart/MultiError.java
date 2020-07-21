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
 * Implementation of {@link Multi} that represents an error, raised during
 * {@link Publisher#subscribe(Subscriber)} by invoking {@link Subscriber#onError(Throwable)}.
 *
 * @param <T> item type
 */
final class MultiError<T> implements Multi<T> {

    // Forked from https://github.com/oracle/helidon/blob/b64be21a5f5c7bbdecd6acf35339c6ee15da0af6/common/reactive/src/main/java/io/helidon/common/reactive/MultiError.java

    private final Throwable error;

    MultiError(Throwable error) {
        this.error = requireNonNull(error, "error");
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        subscriber.onSubscribe(NoopSubscription.INSTANCE);
        subscriber.onError(error);
    }
}
