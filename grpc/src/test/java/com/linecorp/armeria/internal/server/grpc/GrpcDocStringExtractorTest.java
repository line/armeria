/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class GrpcDocStringExtractorTest {

    private static final Map<String, String> DOCSTRINGS = new GrpcDocStringExtractor().getAllDocStrings(
            GrpcDocStringExtractorTest.class.getClassLoader());

    @Test
    void service() {
        assertThat(DOCSTRINGS).containsEntry(
                "armeria.grpc.testing.TestService",
                " A simple service to test the various types of RPCs and experiment with\n" +
                " performance with various types of payload.\n");
    }

    @Test
    void method() {
        assertThat(DOCSTRINGS).containsEntry(
                "armeria.grpc.testing.TestService/UnaryCall",
                " One request followed by one response.\n");
    }

    @Test
    void methodOption() {
        // Currently, Armeria docs doesn't support options / extension.
        // This test verifies that existing comments on options do not break existing logic.
        assertThat(DOCSTRINGS).containsEntry(
                "armeria.grpc.testing.TestService/UnaryCall2",
                " Another method with one request followed by one response.\n");
    }

    @Test
    void message() {
        assertThat(DOCSTRINGS).containsEntry(
                "armeria.grpc.testing.SimpleRequest",
                " Unary request.\n");
    }

    @Test
    void field() {
        assertThat(DOCSTRINGS).containsEntry(
                "armeria.grpc.testing.SimpleRequest/response_type",
                " Desired payload type in the response from the server.\n" +
                " If response_type is RANDOM, server randomly chooses one from other formats.\n");
    }

    @Test
    void nestedMessage() {
        assertThat(DOCSTRINGS).containsEntry(
                "armeria.grpc.testing.SimpleRequest.NestedRequest",
                " A request nested in another request.\n");
    }

    @Test
    void nestedMessageField() {
        assertThat(DOCSTRINGS).containsEntry(
                "armeria.grpc.testing.SimpleRequest.NestedRequest/nested_payload",
                " The payload for a nested request.\n");
    }

    @Test
    void enumType() {
        assertThat(DOCSTRINGS).containsEntry(
                "armeria.grpc.testing.CompressionType",
                " Compression algorithms\n");
    }

    @Test
    void enumValue() {
        assertThat(DOCSTRINGS).containsEntry(
                "armeria.grpc.testing.CompressionType/NONE",
                " No compression\n");
    }

    @Test
    void nestedEnumType() {
        assertThat(DOCSTRINGS).containsEntry(
                "armeria.grpc.testing.SimpleRequest.NestedEnum",
                " An enum nested in a request.\n");
    }

    @Test
    void nestedEnumValue() {
        assertThat(DOCSTRINGS).containsEntry(
                "armeria.grpc.testing.SimpleRequest.NestedEnum/OK",
                " We're ok.\n");
    }
}
