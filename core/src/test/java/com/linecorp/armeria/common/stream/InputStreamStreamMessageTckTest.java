/*
 * Copyright 2023 LINE Corporation
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

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.HttpData;

public class InputStreamStreamMessageTckTest extends StreamMessageVerification<HttpData> {

    @Override
    public StreamMessage<HttpData> createPublisher(long elements) {
        if (elements == 0) {
            return completedPublisher();
        }

        return StreamMessage
                .builder(inputStream(elements))
                .bufferSize(1)
                .build();
    }

    @Override
    public StreamMessage<HttpData> createFailedPublisher() {
        return abortedPublisher();
    }

    @Override
    public StreamMessage<HttpData> createAbortedPublisher(long elements) {
        if (elements == 0) {
            return abortedPublisher();
        }

        final StreamMessage<HttpData> publisher = createPublisher(LongMath.saturatedAdd(elements, 1));
        final AtomicLong produced = new AtomicLong();
        return publisher
                .peek(read -> {
                    if (produced.getAndIncrement() >= elements) {
                        publisher.abort();
                    }
                });
    }

    private static StreamMessage<HttpData> completedPublisher() {
        final StreamMessage<HttpData> publisher = StreamMessage.of(inputStream(0));
        // `InputStreamStreamMessage` doesn't check InputStream's availability so manually set complete.
        publisher.whenComplete().complete(null);
        return publisher;
    }

    private static StreamMessage<HttpData> abortedPublisher() {
        final StreamMessage<HttpData> publisher = StreamMessage.of(inputStream(0));
        publisher.abort();
        return publisher;
    }

    private static InputStream inputStream(long elements) {
        final AtomicLong index = new AtomicLong();
        return new InputStream() {
            @Override
            public int read() throws IOException {
                if (index.getAndIncrement() >= elements) {
                    return -1;
                }
                return 1;
            }
        };
    }
}
