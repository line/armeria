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

import java.io.IOException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.Unpooled;
import reactor.test.StepVerifier;

class ByteStreamMessageOutputStreamTest {

    @Test
    void readAll() {
        final ByteStreamMessage byteStreamMessage = StreamMessage.fromOutputStream(os -> {
            try {
                for (int i = 0; i < 5; i++) {
                    os.write(i);
                }
                os.close(); // should close output stream to complete ByteStreamMessage
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        StepVerifier.create(byteStreamMessage)
                    .expectNext(httpData(0), httpData(1), httpData(2), httpData(3), httpData(4))
                    .verifyComplete();
    }

    @Test
    void skip1() {
        final ByteStreamMessage byteStreamMessage = StreamMessage.fromOutputStream(os -> {
            try {
                for (int i = 0; i < 5; i++) {
                    os.write(i);
                }
                os.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }).range(1, Long.MAX_VALUE);

        StepVerifier.create(byteStreamMessage, 1)
                    .expectNext(httpData(1))
                    .thenRequest(2)
                    .expectNext(httpData(2), httpData(3))
                    .thenRequest(1)
                    .expectNext(httpData(4))
                    .verifyComplete();
    }

    private static HttpData httpData(int b) {
        return HttpData.wrap(Unpooled.buffer(1, 1).writeByte(b));
    }
}
