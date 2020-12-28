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
 * Copyright (c)  2020 Oracle and/or its affiliates. All rights reserved.
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
 *
 */

package com.linecorp.armeria.common.multipart;

import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBufAllocator;
import reactor.core.publisher.Flux;

public class MultipartDecoderTckTest extends PublisherVerification<BodyPart> {

    // Forked from https://github.com/oracle/helidon/blob/9d209a1a55f927e60e15b061700384e438ab5a01/media/multipart/src/test/java/io/helidon/media/multipart/MultiPartDecoderTckTest.java

    public MultipartDecoderTckTest() {
        super(new TestEnvironment(200));
    }

    static Publisher<HttpData> upstream(final long l) {
        final Stream<HttpData> stream =
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
                          );
        return Flux.fromStream(stream);
    }

    @Override
    public Publisher<BodyPart> createPublisher(final long l) {
        return new MultipartDecoder(StreamMessages.toStreamMessage(upstream(l)), "boundary",
                                    ByteBufAllocator.DEFAULT);
    }

    @Override
    public Publisher<BodyPart> createFailedPublisher() {
        return null;
    }

    @Override
    @Test(enabled = false)
    public void required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue() throws Throwable {
        // Long.MAX_VALUE is not suitable size to parse data. MimeParser will throw OutOfMemoryError.
        notVerified();
    }
}
