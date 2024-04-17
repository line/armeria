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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.ThrowableProto;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;

import io.grpc.InternalMetadata;
import io.grpc.InternalStatus;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.protobuf.ProtoUtils;

class MetadataUtilTest {
    private static final Metadata.Key<ThrowableProto> THROWABLE_PROTO_METADATA_KEY =
            ProtoUtils.keyForProto(ThrowableProto.getDefaultInstance());

    private static final Metadata.Key<String> STATUS_KEY =
            InternalMetadata.keyOf(":status", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<String> TEST_ASCII_KEY =
            Metadata.Key.of("testAscii", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<byte[]> TEST_BIN_KEY =
            Metadata.Key.of("testBinary-bin", Metadata.BINARY_BYTE_MARSHALLER);

    private static final ThrowableProto THROWABLE_PROTO = GrpcStatus.serializeThrowable(
            new RuntimeException("test"));

    @Test
    void fillHeadersTest() {
        final HttpHeadersBuilder trailers =
                ResponseHeaders.builder()
                               .endOfStream(true)
                               .add(HttpHeaderNames.STATUS, HttpStatus.OK.codeAsText())
                               .add(HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto")
                               .add(GrpcHeaderNames.GRPC_STATUS, "3")
                               .add(GrpcHeaderNames.GRPC_MESSAGE, "test_grpc_message")
                               .add(GrpcHeaderNames.GRPC_STATUS_DETAILS_BIN,
                                    Base64.getEncoder().encodeToString("test_grpc_details".getBytes()));

        final Metadata metadata = new Metadata();
        // be copied into HttpHeaderBuilder trailers
        metadata.put(TEST_ASCII_KEY, "metadata_test_string");
        metadata.put(TEST_BIN_KEY, "metadata_test_string".getBytes());
        // must not be copied into HttpHeaderBuilder trailers
        metadata.put(STATUS_KEY, "200");
        metadata.put(InternalStatus.CODE_KEY, Status.OK);
        metadata.put(InternalStatus.MESSAGE_KEY, "grpc_message_must_not_be_copied");
        metadata.put(THROWABLE_PROTO_METADATA_KEY, THROWABLE_PROTO);

        MetadataUtil.fillHeaders(metadata, trailers);

        assertThat(trailers.getAll(TEST_ASCII_KEY.originalName())).containsExactly("metadata_test_string");
        assertThat(Base64.getDecoder().decode(trailers.get(TEST_BIN_KEY.originalName())))
                .containsExactly("metadata_test_string".getBytes());
        assertThat(trailers.getAll(HttpHeaderNames.STATUS)).containsExactly(HttpStatus.OK.codeAsText());
        assertThat(trailers.getAll(HttpHeaderNames.CONTENT_TYPE)).containsExactly("application/grpc+proto");
        assertThat(trailers.getAll(GrpcHeaderNames.GRPC_STATUS)).containsExactly("3");
        assertThat(trailers.getAll(GrpcHeaderNames.GRPC_MESSAGE)).containsOnly("test_grpc_message");
        assertThat(Base64.getDecoder().decode(trailers.get(GrpcHeaderNames.GRPC_STATUS_DETAILS_BIN)))
                .containsExactly("test_grpc_details".getBytes());
        assertThat(trailers.getAll(GrpcHeaderNames.ARMERIA_GRPC_THROWABLEPROTO_BIN)).isEmpty();
    }

    @Test
    void copyFromHeadersTest() {
        final HttpHeaders trailers =
                ResponseHeaders.builder()
                               .endOfStream(true)
                               .add(HttpHeaderNames.STATUS, HttpStatus.OK.codeAsText())
                               .add(HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto")
                               .add(GrpcHeaderNames.GRPC_STATUS, "3")
                               .add(GrpcHeaderNames.GRPC_MESSAGE, "test_grpc_message")
                               .add(TEST_ASCII_KEY.originalName(), "test_message")
                               .add(GrpcHeaderNames.ARMERIA_GRPC_THROWABLEPROTO_BIN,
                                    Base64.getEncoder().encodeToString(THROWABLE_PROTO.toByteArray()))
                               .build();

        final Metadata metadata = MetadataUtil.copyFromHeaders(trailers);

        assertThat(metadata.get(TEST_ASCII_KEY)).isEqualTo("test_message");
        // MUST not copy values of :status, grpc-status, grpc-message, armeria.grpc.ThrowableProto-bin
        assertThat(metadata.get(STATUS_KEY)).isNull();
        assertThat(metadata.get(InternalStatus.CODE_KEY)).isNull();
        assertThat(metadata.get(InternalStatus.MESSAGE_KEY)).isNull();
        assertThat(metadata.get(THROWABLE_PROTO_METADATA_KEY)).isNull();
    }
}
