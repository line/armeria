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
package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.stream.LongStream;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.TestEnvironment.ManualSubscriber;
import org.testng.annotations.Test;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageVerification;

import io.netty.buffer.ByteBufAllocator;

public class MultipartDecoderTckTest extends StreamMessageVerification<BodyPart> {

    static Publisher<HttpData> upstream(final long l) {
        final HttpData[] bodyParts =
                LongStream.rangeClosed(1, l)
                          .mapToObj(i -> {
                                        String chunk = "";
                                        if (i == 1L) {
                                            chunk = "--boundary\n";
                                        }
                                        chunk += "Content-Id: part" + l + '\n' +
                                                 '\n' +
                                                 "body " + l + '\n' +
                                                 "--boundary";
                                        if (i == l) {
                                            chunk += "--";
                                        } else {
                                            chunk += "\n";
                                        }
                                        return HttpData.ofUtf8(chunk);
                                    }
                          ).toArray(HttpData[]::new);
        return StreamMessage.of(bodyParts);
    }

    @Override
    public StreamMessage<BodyPart> createPublisher(final long l) {
        return new MultipartDecoder(StreamMessage.of(upstream(l)), "boundary",
                                    ByteBufAllocator.DEFAULT);
    }

    @Override
    public StreamMessage<BodyPart> createFailedPublisher() {
        return null;
    }

    @Nullable
    @Override
    public StreamMessage<BodyPart> createAbortedPublisher(long elements) {
        // MultipartDecoder just delegates to upstream
        return null;
    }

    @Override
    @Test
    public void required_completionFutureMustCompleteOnTermination0() throws Throwable {
        activePublisherTest(0, true, pub -> {
            final ManualSubscriber<BodyPart> sub = env().newManualSubscriber(pub);
            final StreamMessage<?> stream = (StreamMessage<?>) pub;

            // Remove a validation whether a stream is closed on an empty stream from the original test case.
            // Because a MultipartDecoder wraps an input source with DecodedHttpStreamMessage which don't know
            // whether the input source is empty before receiving onComplete().

            // TODO(ikhoon): Gerneralize this test suit?

            assertThat(stream.whenComplete()).isNotDone();
            sub.requestEndOfStream();

            await().untilAsserted(() -> assertThat(stream.whenComplete()).isCompleted());
            assertThat(stream.isOpen()).isFalse();
            assertThat(stream.isEmpty()).isTrue();
            sub.expectNone();
        });
    }

    @Override
    @Test(enabled = false)
    public void required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue() throws Throwable {
        // Long.MAX_VALUE is not suitable size to parse data. MimeParser will throw OutOfMemoryError.
        notVerified();
    }
}
