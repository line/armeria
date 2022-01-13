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
 * under the Licenses
 */

package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.common.AggregatedHttpObject;
import com.linecorp.armeria.common.ContentDisposition;

public class MultipartTest {
    @TempDir
    static Path tempDir;

    @Test
    void collect() {
        final CompletableFuture<List<Object>> collect =
                Multipart.of(
                        BodyPart.of(ContentDisposition.of("form-data", "name1"),
                                    "hello1"),
                        BodyPart.of(ContentDisposition.of("form-data", "name2"),
                                    "hello2"),
                        BodyPart.of(ContentDisposition.of("form-data", "name3", "hello.txt"),
                                    "hello3")
                ).collect(bodyPart -> {
                    if (bodyPart.filename() != null) {
                        final Path path = tempDir.resolve(bodyPart.name());
                        return bodyPart.writeTo(path).thenApply(ignore -> path);
                    }
                    return bodyPart.aggregate().thenApply(AggregatedHttpObject::contentUtf8);
                });

        final List<Object> bodyParts = collect.join();
        assertThat(bodyParts.get(0)).isEqualTo("hello1");
        assertThat(bodyParts.get(1)).isEqualTo("hello2");
        final Path path = (Path) bodyParts.get(2);
        assertThat(path).isEqualTo(tempDir.resolve("name3"));
        assertThat(path).content().isEqualTo("hello3");
    }

    @Test
    void collectTwice() {
        final Multipart multipart =
                Multipart.of(BodyPart.of(ContentDisposition.of("form-data", "name1"), "hello1"));

        multipart.collect(bodyPart -> bodyPart.aggregate()
                                              .thenApply(AggregatedHttpObject::contentUtf8));
        final CompletableFuture<List<Object>> collect =
                multipart.collect(bodyPart -> bodyPart.aggregate()
                                                      .thenApply(AggregatedHttpObject::contentUtf8));
        await().untilAsserted(() -> {
            assertThatThrownBy(collect::join).hasCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void collectHandleNullName() {
        final CompletableFuture<List<Object>> collect = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "name1"),
                            "hello1"),
                BodyPart.of(ContentDisposition.of("form-data"),
                            "hello2"),
                BodyPart.of(ContentDisposition.of("form-data", "name3", "hello1.txt"),
                            "hello3"),
                BodyPart.of(ContentDisposition.builder("form-data").filename("hello2.txt").build(),
                            "hello4")
        ).collect(bodyPart -> {
            if (bodyPart.name() == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (bodyPart.filename() != null) {
                final Path path = tempDir.resolve(bodyPart.name());
                return bodyPart.writeTo(path).thenApply(ignore -> path);
            }
            return bodyPart.aggregate().thenApply(AggregatedHttpObject::contentUtf8);
        });

        await().untilAsserted(() -> {
            assertThatThrownBy(collect::join)
                    .hasCauseInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void collectFunctionThrowingException() {
        final CompletableFuture<List<Object>> collect = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "name1"), "hello1")
        ).collect(bodyPart -> {
            throw new NullPointerException("foo");
        });

        await().untilAsserted(() -> {
            assertThatThrownBy(collect::join)
                    .hasCauseInstanceOf(NullPointerException.class)
                    .hasMessageContaining("foo");
        });
    }

    @Test
    void collectFunctionReturnFutureException() {
        final CompletableFuture<List<Object>> collect = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "name1"), "hello1")
        ).collect(bodyPart -> {
            final CompletableFuture<Void> completableFuture = new CompletableFuture<>();
            completableFuture.completeExceptionally(new NullPointerException("foo"));
            return completableFuture;
        });

        await().untilAsserted(() -> {
            assertThatThrownBy(collect::join)
                    .hasCauseInstanceOf(NullPointerException.class)
                    .hasMessageContaining("foo");
        });
    }
}
