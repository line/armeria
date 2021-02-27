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

package com.linecorp.armeria.common.stream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.testing.TemporaryFolder;

import io.netty.buffer.ByteBufAllocator;

public class PathStreamMessageTckTest extends StreamMessageVerification<HttpData> {

    static TemporaryFolder temporaryFolder = new TemporaryFolder();

    private Path path;

    @BeforeMethod
    public void initialize() throws IOException {
        temporaryFolder.create();
        path = temporaryFolder.newFile();
    }

    @AfterMethod
    public void cleanUp() throws IOException {
        temporaryFolder.delete();
    }

    public PathStreamMessageTckTest() {
        super(new TestEnvironment(200));
    }

    @Override
    public StreamMessage<HttpData> createPublisher(long elements) {
        try {
            final int clamped = Ints.saturatedCast(elements);
            final byte[] bytes = new byte[clamped];
            for (int i = 0; i < clamped; i++) {
                bytes[i] = '0';
            }
            Files.write(path, bytes);
            return new PathStreamMessage(path, ByteBufAllocator.DEFAULT, null,  1);
        } catch (IOException e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    @Override
    public StreamMessage<HttpData> createFailedPublisher() {
        return new PathStreamMessage(Paths.get("/unknown/" + UUID.randomUUID()),
                                     ByteBufAllocator.DEFAULT, null, 1);
    }

    @Override
    public StreamMessage<HttpData> createAbortedPublisher(long elements) {
        if (elements == 0) {
            final StreamMessage<HttpData> publisher = createPublisher(0);
            publisher.abort();
            return publisher;
        }

        final AtomicReference<StreamMessage<HttpData>> stream = new AtomicReference<>();
        final StreamMessage<HttpData> wrapped =
                new StreamMessageWrapper<HttpData>(createPublisher(elements + 1)) {

                    private final AtomicLong produced = new AtomicLong();

                    @Override
                    public void subscribe(Subscriber<? super HttpData> subscriber) {
                        super.subscribe(new Subscriber<HttpData>() {
                            @Override
                            public void onSubscribe(Subscription s) {
                                subscriber.onSubscribe(s);
                            }

                            @Override
                            public void onNext(HttpData value) {
                                subscriber.onNext(value);
                                if (produced.incrementAndGet() == elements) {
                                    stream.get().abort();
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                                subscriber.onError(t);
                            }

                            @Override
                            public void onComplete() {
                                subscriber.onComplete();
                            }
                        });
                    }
                };

        stream.set(wrapped);
        return stream.get();
    }

    @Override
    @Test(enabled = false)
    public void required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue() throws Throwable {
        // PathPublisherTckTest needs to write a temporary file for testing.
        // Long.MAX_VALUE is not a proper size for the test file.
        notVerified();
    }
}
