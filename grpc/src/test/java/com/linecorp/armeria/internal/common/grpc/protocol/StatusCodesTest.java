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
package com.linecorp.armeria.internal.common.grpc.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.grpc.Status.Code;

class StatusCodesTest {
    @Test
    void mustBeInSyncWithUpstream() throws Exception {
        final Map<Integer, String> downstreamFields =
                Arrays.stream(StatusCodes.class.getFields())
                      .filter(f -> {
                          final int mod = f.getModifiers();
                          return Modifier.isPublic(mod) && Modifier.isStatic(mod);
                      }).collect(Collectors.toMap(f -> {
                          try {
                              return (Integer) f.get(null);
                          } catch (IllegalAccessException e) {
                              throw new Error(e);
                          }
                      }, Field::getName));

        final Map<Integer, String> upstreamFields =
                Arrays.stream(Code.values()).collect(Collectors.toMap(Code::value, Code::name));

        assertThat(downstreamFields).isEqualTo(upstreamFields);
    }
}
