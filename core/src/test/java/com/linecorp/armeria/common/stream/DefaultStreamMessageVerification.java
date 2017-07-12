/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.stream;

import java.util.concurrent.atomic.AtomicLong;

public class DefaultStreamMessageVerification extends StreamMessageVerification<Long> {

    @Override
    public StreamMessage<Long> createPublisher(long elements) {
        return createStreamMessage(elements, false);
    }

    static StreamMessage<Long> createStreamMessage(long elements, boolean abort) {
        final DefaultStreamMessage<Long> stream = new DefaultStreamMessage<>();
        if (elements == 0) {
            if (abort) {
                stream.abort();
            } else {
                stream.close();
            }
            return stream;
        }

        final AtomicLong remaining = new AtomicLong(elements);
        stream(elements, abort, remaining, stream);
        return stream;
    }

    private static void stream(long elements, boolean abort,
                               AtomicLong remaining, DefaultStreamMessage<Long> stream) {
        stream.onDemand(() -> {
            for (;;) {
                final long r = remaining.decrementAndGet();
                final boolean written = stream.write(elements - r);
                if (r == 0) {
                    if (abort) {
                        stream.abort();
                    } else {
                        stream.close();
                    }
                    break;
                }

                if (!written) {
                    stream(elements, abort, remaining, stream);
                    break;
                }
            }
        });
    }

    @Override
    public StreamMessage<Long> createFailedPublisher() {
        DefaultStreamMessage<Long> stream = new DefaultStreamMessage<>();
        stream.subscribe(new NoopSubscriber<>());
        return stream;
    }

    @Override
    public StreamMessage<Long> createAbortedPublisher(long elements) {
        return createStreamMessage(elements, true);
    }
}
