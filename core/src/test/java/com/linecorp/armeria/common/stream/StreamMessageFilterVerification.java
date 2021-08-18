/*
 * Copyright 2021 LINE Corporation
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

import java.util.function.Predicate;

import com.linecorp.armeria.common.annotation.Nullable;

public class StreamMessageFilterVerification extends DefaultStreamMessageVerification {

    @Override
    public StreamMessage<Long> createPublisher(long elements) {
        final Predicate<Long> filter;
        if (elements == Long.MAX_VALUE) {
            filter = x -> true;
        } else {
            filter = x -> x % 2 == 0;
            elements *= 2;
        }

        final StreamMessage<Long> upstream = super.createPublisher(elements);

        return upstream.filter(filter);
    }

    @Override
    public StreamMessage<Long> createFailedPublisher() {
        return super.createFailedPublisher().filter(x -> true);
    }

    @Nullable
    @Override
    public StreamMessage<Long> createAbortedPublisher(long elements) {
        return super.createAbortedPublisher(elements).filter(x -> true);
    }
}
