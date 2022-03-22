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

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.buffer.ByteBuf;

/**
 * Decodes a stream of data to N objects.
 *
 * <p>Follow the below steps to decode data using {@link StreamDecoder}.
 * <ol>
 *   <li>Implement your decoding logic in {@link StreamDecoder}.
 *       <pre>{@code
 *       > class FixedLengthDecoder implements StreamDecoder<String> {
 *       >     private final int length;
 *       >
 *       >     FixedLengthDecoder(int length) {
 *       >         this.length = length;
 *       >     }
 *       >
 *       >     @Override
 *       >     public ByteBuf toByteBuf(HttpData in) {
 *       >         return in.byteBuf();
 *       >     }
 *       >
 *       >     @Override
 *       >     public void process(StreamDecoderInput in, StreamDecoderOutput<String> out) {
 *       >         int remaining = in.readableBytes();
 *       >         if (remaining < length) {
 *       >             // The input is not enough to process. Waiting for more data.
 *       >             return;
 *       >         }
 *       >
 *       >         do {
 *       >             // Read data from 'StreamDecoderInput' and
 *       >             // write the processed result to 'StreamDecoderOutput'.
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
 *   <li>Create a decoded {@link StreamMessage} using {@link StreamMessage#decode(StreamDecoder)}
 *       with the {@link StreamDecoder} instance.
 *       <pre>{@code
 *       FixedLengthDecoder decoder = new FixedLengthDecoder(11);
 *       StreamMessage<HttpData> stream = ...;
 *       StreamMessage<String> decoded = stream.decode(decoder);
 *       }</pre>
 *   </li>
 *   <li>Subscribe to the {@link Publisher} of the decoded data and connect to your business logic.
 *       <pre>{@code
 *       import reactor.core.publisher.Flux;
 *       Flux.from(decoded).map(...); // Consume and manipulate the decoded data.
 *       }</pre>
 *   </li>
 * </ol>
 *
 * @param <I> the input type to decode
 * @param <O> the output type of being decoded
 */
@UnstableApi
public interface StreamDecoder<I, O> {

    /**
     * Converts the specified {@link I} type object into a {@link ByteBuf} that is added to
     * {@link StreamDecoderInput}.
     */
    ByteBuf toByteBuf(I in);

    /**
     * Decodes a stream of data to N objects.
     * This method will be called whenever an object is signaled from {@link Publisher}.
     */
    void process(StreamDecoderInput in, StreamDecoderOutput<O> out) throws Exception;

    /**
     * Invoked when {@link HttpData}s are fully consumed.
     */
    default void processOnComplete(StreamDecoderInput in, StreamDecoderOutput<O> out) throws Exception {}

    /**
     * Invoked when a {@link Throwable} is raised while deframing.
     */
    default void processOnError(Throwable cause) {}
}
