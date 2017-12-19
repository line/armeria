/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.server.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

public class TypeSignatureJsonSerializerTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void base() {
        assertThat((Object) mapper.valueToTree(TypeSignature.ofBase("i64")))
                .isEqualTo(JsonNodeFactory.instance.textNode("i64"));
    }

    @Test
    public void named() {
        assertThat((Object) mapper.valueToTree(TypeSignature.ofNamed(URI.class)))
                .isEqualTo(JsonNodeFactory.instance.textNode("java.net.URI"));
    }

    @Test
    public void list() {
        assertThat((Object) mapper.valueToTree(TypeSignature.ofList(TypeSignature.ofBase("binary"))))
                .isEqualTo(JsonNodeFactory.instance.textNode("list<binary>"));
    }

    @Test
    public void set() {
        assertThat((Object) mapper.valueToTree(TypeSignature.ofSet(TypeSignature.ofBase("double"))))
                .isEqualTo(JsonNodeFactory.instance.textNode("set<double>"));
    }

    @Test
    public void map() {
        final TypeSignature str = TypeSignature.ofBase("string");
        assertThat((Object) mapper.valueToTree(TypeSignature.ofMap(str, str)))
                .isEqualTo(JsonNodeFactory.instance.textNode("map<string, string>"));
    }

    @Test
    public void unresolved() {
        assertThat((Object) mapper.valueToTree(TypeSignature.ofUnresolved("OddType")))
                .isEqualTo(JsonNodeFactory.instance.textNode("?OddType"));
    }
}
