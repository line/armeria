/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.server.http.dynamic;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpRequest;

public class DynamicHttpServiceBuilderTest {

    @Test
    public void testBuildingAnnotatedService_success() {
        DynamicHttpServiceBuilder builder = new DynamicHttpServiceBuilder();
        builder.addMappings(new Object() {
            @Get
            @Path("/ok/1")
            public Object ok1(RequestContext context, AggregatedHttpMessage message) {
                return null;
            }

            @Get
            @Path("/ok/2")
            public Object ok2(RequestContext context, HttpRequest request) {
                return null;
            }
        });
    }

    @Test
    public void testBuildingAnnotatedService_failure() {
        DynamicHttpServiceBuilder builder = new DynamicHttpServiceBuilder();
        assertThatThrownBy(() -> builder.addMappings(new Object() {
            @Get
            @Path("/more-then-one-request-parameter")
            public Object moreThanOneRequestParameter(AggregatedHttpMessage message, HttpRequest request) {
                return null;
            }
        })).isInstanceOf(IllegalArgumentException.class);
    }
}
