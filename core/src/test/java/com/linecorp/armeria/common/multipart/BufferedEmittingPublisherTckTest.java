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
 */
package com.linecorp.armeria.common.multipart;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class BufferedEmittingPublisherTckTest extends PublisherVerification<Integer> {

    // Forked from https://github.com/oracle/helidon/blob/e8979b24d75886d66e938e2253c4253db8b55955/common/reactive/src/test/java/io/helidon/common/reactive/BufferedEmittingPublisherTckTest.java

    private static final Logger logger = LoggerFactory.getLogger(BufferedEmittingPublisherTckTest.class);

    private static ExecutorService executor;

    @BeforeMethod
    public void beforeClass() {
        executor = new ThreadPoolExecutor(4, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                                          new SynchronousQueue<>());
    }

    @AfterMethod
    public void afterClass() {
        executor.shutdownNow();
    }

    public BufferedEmittingPublisherTckTest() {
        super(new TestEnvironment(200));
    }

    @Override
    public Publisher<Integer> createPublisher(long l) {
        final CountDownLatch emitLatch = new CountDownLatch((int) l);
        final BufferedEmittingPublisher<Integer> emitter = new BufferedEmittingPublisher<>();
        executor.submit(() -> {
            //stochastic test of emit methods being thread-safe
            IntStream.range(0, (int) l)
                    .boxed()
                    .parallel()
                    .forEach(item -> {
                        emitter.emit(item);
                        emitLatch.countDown();
                    });
        });
        executor.submit(() -> {
            try {
                emitLatch.await();
                emitter.complete();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        });
        return emitter;
    }

    @Override
    public Publisher<Integer> createFailedPublisher() {
        final BufferedEmittingPublisher<Integer> emitter = new BufferedEmittingPublisher<>();
        emitter.fail(new RuntimeException());
        return emitter;
    }

    @Override
    public long maxElementsFromPublisher() {
        return Integer.MAX_VALUE - 1;
    }

    @Override
    public void stochastic_spec103_mustSignalOnMethodsSequentially() throws Throwable {
        for (int i = 0; i < 100; i++) {
            super.stochastic_spec103_mustSignalOnMethodsSequentially();
        }
    }
}
