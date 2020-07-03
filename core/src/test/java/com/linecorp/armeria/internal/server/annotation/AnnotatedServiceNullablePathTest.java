/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Path;

class AnnotatedServiceNullablePathTest {

    @Test
    void testNullablePathSuccessService() {
        Server.builder()
              .annotatedService("/1", new Object() {
                  @Get
                  public HttpResponse nullable() {
                      return HttpResponse.of(HttpStatus.OK);
                  }
              })
              .annotatedService("/2", new Object() {
                  @Get("")
                  public HttpResponse nullable() {
                      return HttpResponse.of(HttpStatus.OK);
                  }
              })
              .build();
    }

    @Test
    void testNullablePathFailureService() {
        assertThatThrownBy(() -> {
            Server.builder()
                  .annotatedService("/1", new Object() {
                      @Get("")
                      @Path("/")
                      public HttpResponse nullable() {
                          return HttpResponse.of(HttpStatus.OK);
                      }
                  })
                  .build();
        }).isInstanceOf(IllegalArgumentException.class);
    }
}
