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

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.Test;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.stream.StreamMessage;

import reactor.core.publisher.Flux;

public class MultipartEncoderTckTest extends PublisherVerification<HttpData> {

    // Forked from https://github.com/oracle/helidon/blob/9d209a1a55f927e60e15b061700384e438ab5a01/media/multipart/src/test/java/io/helidon/media/multipart/MultiPartEncoderTckTest.java

    static {
        // Make sure the worker group is initialized.
        CommonPools.workerGroup().next().submit(() -> {});
    }

    public MultipartEncoderTckTest() {
        super(new TestEnvironment(500));
    }

    @Override
    public Publisher<HttpData> createPublisher(final long l) {
        final Flux<BodyPart> source =
                Flux.fromStream(LongStream.rangeClosed(1, l)
                                          .mapToObj(i -> BodyPart.builder()
                                                                 .content("part" + i)
                                                                 .build()
                                          ));
        return new MultipartEncoder(StreamMessage.of(source), "boundary");
    }

    @Override
    public Publisher<HttpData> createFailedPublisher() {
        return null;
    }

    @Override
    public void required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber()
            throws Throwable {
        super.required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber();
    }

    @Test(enabled = false)
    @Override
    public void required_createPublisher3MustProduceAStreamOfExactly3Elements() throws Throwable {
        // disabled because the encoder always emits one more item for the closing boundary
    }

    @Test(enabled = false)
    @Override
    public void required_createPublisher1MustProduceAStreamOfExactly1Element() throws Throwable {
        // disabled because the encoder always emits one more item for the closing boundary
    }

    @Test(enabled = false)
    @Override
    public void required_spec102_maySignalLessThanRequestedAndTerminateSubscription() throws Throwable {
        // disabled because the encoder always emits one more item for the closing boundary
    }

    @Test(enabled = false)
    @Override
    public void required_spec105_mustSignalOnCompleteWhenFiniteStreamTerminates() throws Throwable {
        // disabled because the encoder always emits one more item for the closing boundary
    }

    @Test(enabled = false)
    @Override
    public void required_spec107_mustNotEmitFurtherSignalsOnceOnCompleteHasBeenSignalled() throws Throwable {
        // disabled because the encoder always emits one more item for the closing boundary
    }

    @Test(enabled = false)
    @Override
    public void required_spec317_mustSupportACumulativePendingElementCountUpToLongMaxValue() throws Throwable {
        // disabled because the encoder always emits one more item for the closing boundary
    }

    @Test(enabled = false)
    @Override
    public void required_spec317_mustSupportAPendingElementCountUpToLongMaxValue() throws Throwable {
        // disabled because the encoder always emits one more item for the closing boundary
    }

    @Test(enabled = false)
    @Override
    public void stochastic_spec103_mustSignalOnMethodsSequentially() throws Throwable {
        // disabled because the encoder always emits one more item for the closing boundary
    }
}
