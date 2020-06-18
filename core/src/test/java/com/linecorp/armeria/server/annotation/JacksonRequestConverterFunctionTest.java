/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.ParameterizedType;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;

public class JacksonRequestConverterFunctionTest {

    private static final RequestConverterFunction function = new JacksonRequestConverterFunction();
    private static final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
    private static final AggregatedHttpRequest req = mock(AggregatedHttpRequest.class);

    static final String JSON_TEXT = "{\"key\": \"value\"}";
    static final String JSON_ARRAY = "[1, 2, 3]";

    @Test(expected = FallthroughException.class)
    public void jsonTextToByteArray() throws Exception {
        when(req.contentType()).thenReturn(MediaType.JSON);
        when(req.content(StandardCharsets.UTF_8)).thenReturn(JSON_TEXT);

        function.convertRequest(ctx, req, byte[].class, null);
    }

    @Test(expected = FallthroughException.class)
    public void jsonTextToHttpData() throws Exception {
        when(req.contentType()).thenReturn(MediaType.JSON);
        when(req.content(StandardCharsets.UTF_8)).thenReturn(JSON_TEXT);

        function.convertRequest(ctx, req, HttpData.class, null);
    }

    @Test(expected = FallthroughException.class)
    public void jsonTextToString() throws Exception {
        when(req.contentType()).thenReturn(MediaType.JSON);
        when(req.content(StandardCharsets.UTF_8)).thenReturn(JSON_TEXT);

        function.convertRequest(ctx, req, String.class, null);
    }

    @Test
    public void jsonTextToTreeNode() throws Exception {
        when(req.contentType()).thenReturn(MediaType.JSON);
        when(req.content(StandardCharsets.UTF_8)).thenReturn(JSON_TEXT);

        assertThat(function.convertRequest(ctx, req, TreeNode.class, null))
                .isEqualTo(new ObjectMapper().readTree(JSON_TEXT));
    }

    @Test
    public void jsonTextToJsonRequest() throws Exception {
        when(req.contentType()).thenReturn(MediaType.JSON);
        when(req.content(StandardCharsets.UTF_8)).thenReturn(JSON_TEXT);

        final Object result = function.convertRequest(ctx, req, JsonRequest.class, null);
        assertThat(result).isInstanceOf(JsonRequest.class);
    }

    @Test
    public void jsonArrayToListOfInteger() throws Exception {
        when(req.contentType()).thenReturn(MediaType.JSON);
        when(req.content(StandardCharsets.UTF_8)).thenReturn(JSON_ARRAY);

        final Object result = function.convertRequest(
                ctx, req, List.class,
                (ParameterizedType) new TypeReference<List<Integer>>() {}.getType());

        assertThat(result).isEqualTo(ImmutableList.of(1, 2, 3));
    }

    @Test
    public void jsonArrayToListOfLong() throws Exception {
        when(req.contentType()).thenReturn(MediaType.JSON);
        when(req.content(StandardCharsets.UTF_8)).thenReturn(JSON_ARRAY);

        final Object result = function.convertRequest(
                ctx, req, List.class,
                (ParameterizedType) new TypeReference<List<Long>>() {}.getType());

        assertThat(result).isEqualTo(ImmutableList.of(1L, 2L, 3L));
    }

    @Test
    public void jsonArrayToTreeNode() throws Exception {
        when(req.contentType()).thenReturn(MediaType.JSON);
        when(req.content(StandardCharsets.UTF_8)).thenReturn(JSON_ARRAY);

        final Object result = function.convertRequest(ctx, req, TreeNode.class, null);

        assertThat(result).isEqualTo(new ObjectMapper().readTree(JSON_ARRAY));
    }

    @Test
    public void jsonArrayToListOfString() throws Exception {
        when(req.contentType()).thenReturn(MediaType.JSON);
        when(req.content(StandardCharsets.UTF_8)).thenReturn(JSON_ARRAY);

        final Object result = function.convertRequest(
                ctx, req, List.class,
                (ParameterizedType) new TypeReference<List<String>>() {}.getType());

        assertThat(result).isEqualTo(ImmutableList.of("1", "2", "3"));
    }

    static class JsonRequest {
        private String key;

        public void setKey(String key) {
            this.key = key;
        }
    }
}
