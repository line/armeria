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
 * under the License.
 */
package com.linecorp.armeria.spring.web.reactive;

import org.junit.Test;
import java.util.Arrays;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link HttpStatusUtil}
 */
public class HttpStatusUtilTest {

    private final Stream<Integer> statusCodes = Arrays.stream(org.springframework.http.HttpStatus.values())
            .map(org.springframework.http.HttpStatus::value);

    @Test
    public void convertSpringStatusToArmeriaStatus() throws AssertionError {
        statusCodes.map(org.springframework.http.HttpStatus::valueOf).forEach(
                (springStatus) -> {
                    int code = springStatus.value();
                    com.linecorp.armeria.common.HttpStatus convertedStatus =
                        HttpStatusUtil.toArmeriaStatus(springStatus);
                    assertThat(convertedStatus.code() == code).isTrue();
                    // default reasonPhrase is created
                    assertNotNull(convertedStatus.reasonPhrase());
                }
        );
    }

    @Test
    public void convertArmeriaStatusToSpringStatus() throws AssertionError {
        statusCodes.map(com.linecorp.armeria.common.HttpStatus::valueOf).forEach(
                (armeriaStatus) -> {
                    int code = armeriaStatus.code();
                    org.springframework.http.HttpStatus convertedStatus =
                            HttpStatusUtil.toSpringStatus(armeriaStatus);
                    assertThat(convertedStatus.value() == code).isTrue();
                }
        );
    }
}
