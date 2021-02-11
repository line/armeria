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

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBufAllocator;
import reactor.test.StepVerifier;

class PathStreamMessageTest {

    @Test
    void readFile() {
        final Path path = Paths.get("src/test/resources/com/linecorp/armeria/common/stream/test.txt");
        final StreamMessage<HttpData> publisher = StreamMessage.of(path, ByteBufAllocator.DEFAULT, 12);
        StepVerifier.create(publisher)
                    .expectNext(HttpData.ofAscii("A1234567890\n"))
                    .expectNext(HttpData.ofAscii("B1234567890\n"))
                    .expectNext(HttpData.ofAscii("C1234567890\n"))
                    .expectNext(HttpData.ofAscii("D1234567890\n"))
                    .expectNext(HttpData.ofAscii("E1234567890\n"))
                    .expectComplete()
                    .verify();
    }
}
