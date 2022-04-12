/*
 * Copyright 2022 LINE Corporation
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

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

import com.linecorp.armeria.common.HttpData;

import reactor.core.publisher.Flux;

@Test
public class DefaultByteStreamMessageTckTest extends PublisherVerification<HttpData> {

    public DefaultByteStreamMessageTckTest() {
        super(new TestEnvironment(200));
    }

    @Override
    public Publisher<HttpData> createPublisher(long elements) {
        if (elements == 0) {
            return ByteStreamMessage.of(StreamMessage.of());
        }
        final Flux<HttpData> publisher = Flux.range(0, (int) elements)
                                             .map(i -> HttpData.ofUtf8(String.valueOf(i)));
        return ByteStreamMessage.of(publisher)
                                .bufferSize(1)
                                .takeBytes((int) elements);
    }

    @Override
    public Publisher<HttpData> createFailedPublisher() {
        return null;
    }

    @Override
    public long maxElementsFromPublisher() {
        return Integer.MAX_VALUE;
    }
}
