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

import static java.util.Objects.requireNonNull;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMessage;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.buffer.ByteBuf;

/**
 * Decodes a stream of {@link HttpObject}s to N objects.
 *
 * <p>Follow the below steps to decode HTTP payload using {@link HttpDecoder}.
 * <ol>
 *   <li>Implement your decoding logic in {@link HttpDecoder}.
 *       <pre>{@code
 *       > class FixedLengthDecoder implements HttpDecoder<String> {
 *       >     private final int length;
 *       >
 *       >     FixedLengthDecoder(int length) {
 *       >         this.length = length;
 *       >     }
 *       >
 *       >     @Override
 *       >     public void process(HttpDecoderInput in, HttpDecoderOutput<String> out) {
 *       >         int remaining = in.readableBytes();
 *       >         if (remaining < length) {
 *       >             // The input is not enough to process. Waiting for more data.
 *       >             return;
 *       >         }
 *       >
 *       >         do {
 *       >             // Read data from 'HttpDecoderInput' and
 *       >             // write the processed result to 'HttpDecoderOutput'.
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
 *   <li>Create a decoded {@link StreamMessage} using {@link HttpMessage#decode(HttpDecoder)}
 *       with the {@link HttpDecoder} instance.
 *       <pre>{@code
 *       FixedLengthDecoder decoder = new FixedLengthDecoder(11);
 *       HttpRequest req = ...;
 *       StreamMessage<String> decoded = req.decode(decoder);
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
 * @param <T> the result type of being decoded
 */
@UnstableApi
public interface HttpDecoder<T> extends StreamDecoder<HttpData, T> {

    @Override
    default ByteBuf toByteBuf(HttpData in) {
        requireNonNull(in, "in");
        // HttpHeaders is handled as is by processXXXHeaders(), processTrailers().
        return in.byteBuf();
    }

    /**
     * Decodes an informational {@link ResponseHeaders} to N objects.
     */
    default void processInformationalHeaders(ResponseHeaders in, StreamDecoderOutput<T> out) throws Exception {}

    /**
     * Decodes a non-informational {@link HttpHeaders} to N objects.
     */
    default void processHeaders(HttpHeaders in, StreamDecoderOutput<T> out) throws Exception {}

    /**
     * Decodes a {@link HttpHeaders trailers} to N objects.
     */
    default void processTrailers(HttpHeaders in, StreamDecoderOutput<T> out) throws Exception {}
}
