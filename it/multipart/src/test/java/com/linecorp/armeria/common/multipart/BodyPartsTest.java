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
 * under the Licenses
 */

package com.linecorp.armeria.common.multipart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.multipart.BodyParts.CollectedBodyParts;

public class BodyPartsTest {
    @TempDir
    static Path tempDir;

    @Test
    void collect() {
        final CompletableFuture<CollectedBodyParts> collect = BodyParts.collect(Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "name1"),
                            "hello1"),
                BodyPart.of(ContentDisposition.of("form-data", "name2"),
                            "hello2"),
                BodyPart.of(ContentDisposition.of("form-data", "name3", "hello.txt"),
                            "hello3")
        ), name -> tempDir.resolve(name));

        final CollectedBodyParts bodyParts = collect.join();
        assertThat(bodyParts.queryParams().get("name1")).isEqualTo("hello1");
        assertThat(bodyParts.queryParams().get("name2")).isEqualTo("hello2");
        final Path path = bodyParts.files().get("name3").get(0);
        assertThat(path).isEqualTo(tempDir.resolve("name3"));
        assertThat(path).content().isEqualTo("hello3");
    }

    @Test
    void collectWithNullName() {
        final CompletableFuture<CollectedBodyParts> collect = BodyParts.collect(Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "name1"),
                            "hello1"),
                BodyPart.of(ContentDisposition.of("form-data"),
                            "hello2"),
                BodyPart.of(ContentDisposition.of("form-data", "name3", "hello1.txt"),
                            "hello3"),
                BodyPart.of(ContentDisposition.builder("form-data").filename("hello2.txt").build(),
                            "hello4")
        ), name -> tempDir.resolve(name));

        final CollectedBodyParts bodyParts = collect.join();
        assertThat(bodyParts.queryParams().size()).isOne();
        assertThat(bodyParts.files().get("name3").size()).isOne();
        assertThat(bodyParts.files().size()).isOne();
    }

    @Test
    void collectWithNullMappingPath() {
        final CompletableFuture<CollectedBodyParts> collect = BodyParts.collect(Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "name1"),
                            "hello1"),
                BodyPart.of(ContentDisposition.of("form-data", "name2"),
                            "hello2"),
                BodyPart.of(ContentDisposition.of("form-data", "name3", "hello1.txt"),
                            "hello3"),
                BodyPart.of(ContentDisposition.of("form-data", "name4", "hello2.txt"),
                            "hello4")
        ), name -> {
            if ("name3".equals(name)) {
                //intentional to produce NPE
                //noinspection ConstantConditions
                return null;
            }
            return tempDir.resolve(name);
        });

        await().untilAsserted(() -> {
            assertThatThrownBy(collect::join)
                    .hasCauseInstanceOf(NullPointerException.class)
                    .hasMessageContaining("mappingFileName from collect returns null");
        });
    }
}
