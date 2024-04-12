/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.client.kubernetes;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.fabric8.kubernetes.client.http.AbstractHttpLoggingInterceptorTest;
import io.fabric8.kubernetes.client.http.HttpClient;

@SuppressWarnings("JUnitTestCaseWithNoTests")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArmeriaHttpHttpLoggingInterceptorTest extends AbstractHttpLoggingInterceptorTest {

    @AfterEach
    void afterEach() throws Exception {
        final Field field = AbstractHttpLoggingInterceptorTest.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        final HttpClient httpClient = (HttpClient) field.get(this);
        httpClient.close();
    }

    @Override
    protected HttpClient.Factory getHttpClientFactory() {
        return new ArmeriaHttpClientFactory();
    }
}
