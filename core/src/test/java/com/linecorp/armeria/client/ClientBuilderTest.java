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

import java.net.MalformedURLException;
import java.net.URI;

import org.junit.jupiter.api.Test;

/**
 * Test for {@link ClientBuilder}.
 */
class ClientBuilderTest {

    @Test
    void nonePlusSchemeProvided() {
        final WebClient client = new ClientBuilder("none+https://google.com/")
                .build(WebClient.class);
        assertThat(client.uri().toString()).isEqualTo("https://google.com/");
    }

    @Test
    void nonePlusSchemeUriToUrl() throws MalformedURLException {
        final WebClient client = new ClientBuilder("none+https://google.com/")
                .build(WebClient.class);
        assertThat(client.uri().toURL()).isEqualTo(URI.create("https://google.com/").toURL());
    }

    @Test
    void noSchemeShouldDefaultToNone() {
        final WebClient client = new ClientBuilder("https://google.com/")
                .build(WebClient.class);
        assertThat(client.uri().toString()).isEqualTo("https://google.com/");
    }
}
