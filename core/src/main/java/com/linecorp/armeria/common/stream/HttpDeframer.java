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

import java.util.function.Function;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * A {@link Processor} implementation that decodes a stream of {@link HttpObject}s to N objects.
 *
 * <p>Follow the below steps to deframe HTTP payload using {@link HttpDeframer}.
 * <ol>
 *   <li>Implement your deframing logic in {@link HttpDeframerHandler}.
 *       <pre>{@code
 *       > class FixedLengthDecoder implements HttpDeframerHandler<String> {
 *       >     private final int length;
 *       >
 *       >     FixedLengthDecoder(int length) {
 *       >         this.length = length;
 *       >     }
 *       >
 *       >     @Override
 *       >     public void process(HttpDeframerInput in, HttpDeframerOutput<String> out) {
 *       >         int remaining = in.readableBytes();
 *       >         if (remaining < length) {
 *       >             // The input is not enough to process. Waiting for more data.
 *       >             return;
 *       >         }
 *       >
 *       >         do {
 *       >             // Read data from 'HttpDeframerInput' and
 *       >             // write the processed result to 'HttpDeframerOutput'.
 *       >             ByteBuf buf = in.readBytes(length);
 *       >             out.add(buf.toString(StandardCharsets.UTF_8));
 *       >             // Should release the returned 'ByteBuf'
 *       >             buf.release();
 *       >             remaining -= length;
 *       >         } while (remaining >= length);
 *       >     }
 *       > }
 *       }</pre>
 *   </li>
 *   <li>Create an {@link HttpDeframer} with the {@link HttpDeframerHandler} instance.
 *       <pre>{@code
 *       FixedLengthDecoder decoder = new FixedLengthDecoder(11);
 *       HttpDeframer<String> deframer = HttpDeframer.of(decoder, ByteBufAllocator.DEFAULT);
 *       }</pre>
 *   </li>
 *   <li>Subscribe to an {@link HttpRequest} using the {@link HttpDeframer}.
 *       <pre>{@code
 *       HttpRequest request = ...;
 *       request.subscribe(deframer);
 *       }</pre>
 *   </li>
 *   <li>Subscribe to the {@link Publisher} of the deframed data and connect to your business logic.
 *       <pre>{@code
 *       import reactor.core.publisher.Flux;
 *       Flux.from(deframer).map(...); // Consume and manipulate the deframed data.
 *       }</pre>
 *   </li>
 * </ol>
 */
@UnstableApi
public interface HttpDeframer<T> extends Processor<HttpObject, T>, StreamMessage<T> {
    /**
     * Returns a new {@link HttpDeframer} with the specified {@link HttpDeframerHandler} and
     * {@link ByteBufAllocator}.
     */
    static <T> HttpDeframer<T> of(HttpDeframerHandler<T> handler, ByteBufAllocator alloc) {
        return of(handler, alloc, HttpData::byteBuf);
    }

    /**
     * Returns a new {@link HttpDeframer} with the specified {@link HttpDeframerHandler},
     * {@link ByteBufAllocator} and {@code byteBufConverter}.
     */
    static <T> HttpDeframer<T> of(HttpDeframerHandler<T> handler, ByteBufAllocator alloc,
                                  Function<? super HttpData, ? extends ByteBuf> byteBufConverter) {
        return new DefaultHttpDeframer<>(handler, alloc, byteBufConverter);
    }
}
