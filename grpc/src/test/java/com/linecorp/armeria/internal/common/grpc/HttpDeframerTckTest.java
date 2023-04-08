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

package com.linecorp.armeria.internal.common.grpc;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import org.reactivestreams.Publisher;
import org.reactivestreams.tck.PublisherVerification;
import org.reactivestreams.tck.TestEnvironment;
import org.testng.annotations.AfterTest;
import org.testng.annotations.Ignore;
import org.testng.annotations.Test;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.grpc.protocol.DeframedMessage;
import com.linecorp.armeria.common.stream.PublisherBasedStreamMessage;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.DecompressorRegistry;
import io.netty.buffer.ByteBuf;
import reactor.core.publisher.Flux;

@Test
public class HttpDeframerTckTest extends PublisherVerification<DeframedMessage> {

    private static final TransportStatusListener noopListener = (status, metadata) -> {};

    private static final HttpData DATA =
            HttpData.wrap(GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf()));

    private final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    private final List<ByteBuf> byteBufs = new ArrayList<>();

    public HttpDeframerTckTest() {
        super(new TestEnvironment(200));
    }

    @AfterTest
    void afterTest() {
        for (ByteBuf byteBuf : byteBufs) {
            byteBuf.release();
        }
        byteBufs.clear();
    }

    @Override
    public Publisher<DeframedMessage> createPublisher(long elements) {
        final HttpData[] data = LongStream.range(0, elements)
                                          .mapToObj(unused -> DATA)
                                          .toArray(HttpData[]::new);
        final StreamMessage<HttpData> source = StreamMessage.of(data);

        final HttpStreamDeframer deframer =
                new HttpStreamDeframer(DecompressorRegistry.getDefaultInstance(), ctx, noopListener,
                                       null, -1, false, true);
        final StreamMessage<DeframedMessage> deframed = source.decode(deframer);

        return Flux.from(deframed).doOnNext(message -> byteBufs.add(message.buf()));
    }

    @Override
    public Publisher<DeframedMessage> createFailedPublisher() {
        final StreamMessage<HttpData> source =
                new PublisherBasedStreamMessage<>(Flux.error(new RuntimeException()));
        final HttpStreamDeframer deframer =
                new HttpStreamDeframer(DecompressorRegistry.getDefaultInstance(), ctx, noopListener,
                                       null, -1, false, true);
        return source.decode(deframer);
    }

    @Ignore
    @Override
    public void required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue() throws Throwable {
        // Long.MAX_VALUE is too big to be contained in an array.
    }
}
