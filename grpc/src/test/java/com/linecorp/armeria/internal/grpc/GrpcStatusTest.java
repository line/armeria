/*
 * Copyright 2018 LINE Corporation
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
/*
 * Copyright 2014, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.internal.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

import com.linecorp.armeria.common.HttpStatus;

import io.grpc.Status;

public class GrpcStatusTest {
    @Test
    public void fromGrpcStatusCode() {
        assertThat(GrpcStatus.fromGrpcStatusCode(Status.OK.getCode())).isEqualTo(HttpStatus.OK);

        assertThat(GrpcStatus.fromGrpcStatusCode(Status.CANCELLED.getCode()))
                .isEqualTo(HttpStatus.CLIENT_CLOSED_REQUEST);

        assertThat(GrpcStatus.fromGrpcStatusCode(Status.UNKNOWN.getCode()))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(GrpcStatus.fromGrpcStatusCode(Status.INTERNAL.getCode()))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(GrpcStatus.fromGrpcStatusCode(Status.DATA_LOSS.getCode()))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(GrpcStatus.fromGrpcStatusCode(Status.INVALID_ARGUMENT.getCode()))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(GrpcStatus.fromGrpcStatusCode(Status.FAILED_PRECONDITION.getCode()))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(GrpcStatus.fromGrpcStatusCode(Status.OUT_OF_RANGE.getCode()))
                .isEqualTo(HttpStatus.BAD_REQUEST);

        assertThat(GrpcStatus.fromGrpcStatusCode(Status.DEADLINE_EXCEEDED.getCode()))
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);

        assertThat(GrpcStatus.fromGrpcStatusCode(Status.NOT_FOUND.getCode())).isEqualTo(HttpStatus.NOT_FOUND);

        assertThat(GrpcStatus.fromGrpcStatusCode(Status.ALREADY_EXISTS.getCode()))
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(GrpcStatus.fromGrpcStatusCode(Status.ABORTED.getCode())).isEqualTo(HttpStatus.CONFLICT);

        assertThat(GrpcStatus.fromGrpcStatusCode(Status.PERMISSION_DENIED.getCode()))
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(GrpcStatus.fromGrpcStatusCode(Status.UNAUTHENTICATED.getCode()))
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(GrpcStatus.fromGrpcStatusCode(Status.RESOURCE_EXHAUSTED.getCode()))
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(GrpcStatus.fromGrpcStatusCode(Status.UNIMPLEMENTED.getCode()))
                .isEqualTo(HttpStatus.NOT_IMPLEMENTED);

        assertThat(GrpcStatus.fromGrpcStatusCode(Status.UNAVAILABLE.getCode()))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
