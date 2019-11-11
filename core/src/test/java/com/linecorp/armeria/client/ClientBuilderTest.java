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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

public class ClientBuilderTest {

    @Test
    public void nonePlusSchemeProvided() {
        final HttpClient client = new ClientBuilder("none+https://google.com/").build(HttpClient.class);
        assertThat(client.uri().toString()).isEqualTo("https://google.com/");
    }

    @Test
    public void nonePlusSchemeUriToUrl() {
        final HttpClient client = new ClientBuilder("none+https://google.com/").build(HttpClient.class);
        assertThatCode(() -> client.uri().toURL()).doesNotThrowAnyException();
    }

    @Test
    public void noSchemeShouldDefaultToNone() {
        final HttpClient client = new ClientBuilder("https://google.com/").build(HttpClient.class);
        assertThat(client.uri().toString()).isEqualTo("https://google.com/");
    }
}
