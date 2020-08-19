/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.grpc;

import static com.linecorp.armeria.common.grpc.GrpcSerializationFormats.JSON;
import static com.linecorp.armeria.common.grpc.GrpcSerializationFormats.JSON_WEB;
import static com.linecorp.armeria.common.grpc.GrpcSerializationFormats.PROTO;
import static com.linecorp.armeria.common.grpc.GrpcSerializationFormats.PROTO_WEB;
import static com.linecorp.armeria.common.grpc.GrpcSerializationFormats.PROTO_WEB_TEXT;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GrpcSerializationFormatsTest {

    @Test
    void allFormatsAreRegistered() {
        assertThat(GrpcSerializationFormats.values())
                .containsExactlyInAnyOrder(PROTO, JSON, PROTO_WEB, JSON_WEB, PROTO_WEB_TEXT);
    }
}
