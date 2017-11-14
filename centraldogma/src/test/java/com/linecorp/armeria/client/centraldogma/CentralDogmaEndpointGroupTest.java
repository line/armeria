/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.client.centraldogma;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.ClassRule;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.centraldogma.CentralDogmaCodec;
import com.linecorp.centraldogma.client.CentralDogma;
import com.linecorp.centraldogma.common.Author;
import com.linecorp.centraldogma.common.Change;
import com.linecorp.centraldogma.common.Query;
import com.linecorp.centraldogma.common.Revision;
import com.linecorp.centraldogma.testing.CentralDogmaRule;

public class CentralDogmaEndpointGroupTest {

    @ClassRule
    public static final CentralDogmaRule dogma = new CentralDogmaRule() {
        @Override
        protected void scaffold(CentralDogma client) {
            client.createProject("a").join();
            client.createRepository("a", "b").join();
        }
    };

    @Test
    public void testJson() throws Exception {
        dogma.client().push("a", "b", Revision.HEAD, Author.SYSTEM, "Add route.json",
                    Change.ofJsonUpsert("/route.json",
                                        '[' +
                                            "\"foo.com:9999\" ," +
                                            "\"bar.com:1234:1234\" ," +
                                            "\"foo.bar:11111:3333\"" +
                                        ']'))
              .join();

        CentralDogmaEndpointGroup<JsonNode> eg = new CentralDogmaEndpointGroup(
                dogma.client(), "a", "b",
                CentralDogmaCodec.DEFAULT_JSON_CODEC,
                Query.identity("/route.json"),
                2, TimeUnit.SECONDS
        );

        assertThat(eg.endpoints()).containsExactlyInAnyOrder(
                Endpoint.of("foo.com", 9999),
                Endpoint.of("bar.com", 1234, 1234),
                Endpoint.of("foo.bar", 11111, 3333)
        );

        eg.close();
    }

    @Test
    public void testText() throws Exception {
        dogma.client().push("a", "b", Revision.HEAD, Author.SYSTEM, "Add route.txt",
                    Change.ofTextUpsert("/route.txt",
                                            "localhost:9999 \n" +
                                            "localhost:1234:1234 \n" +
                                            "localhost:11111:3333"))
              .join();

        CentralDogmaEndpointGroup<String> eg = new CentralDogmaEndpointGroup(
                dogma.client(), "a", "b",
                CentralDogmaCodec.DEFAULT_STRING_CODEC,
                Query.identity("/route.txt"),
                2, TimeUnit.SECONDS
        );

        assertThat(eg.endpoints()).containsExactlyInAnyOrder(
                Endpoint.of("localhost", 9999),
                Endpoint.of("localhost", 1234, 1234),
                Endpoint.of("localhost", 11111, 3333)
        );

        eg.close();
    }
}
