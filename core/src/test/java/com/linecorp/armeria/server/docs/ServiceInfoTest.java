/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;

import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TType;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.FooService.bar3_args;
import com.linecorp.armeria.service.test.thrift.main.FooStruct;

public class ServiceInfoTest {

    @Test
    public void fooServiceTest() throws Exception {
        final ServiceInfo service =
                ServiceInfo.of(FooService.class,
                               Arrays.asList(
                                       EndpointInfo.of("*", "/foo", "a", SerializationFormat.THRIFT_BINARY,
                                                       EnumSet.of(SerializationFormat.THRIFT_BINARY)),
                                       EndpointInfo.of("*", "/debug/foo", "b", SerializationFormat.THRIFT_TEXT,
                                                       EnumSet.of(SerializationFormat.THRIFT_TEXT))),
                               ImmutableMap.of(bar3_args.class, new bar3_args().setIntVal(10)),
                               ImmutableMap.of("foobar", "barbaz"));

        assertThat(service.endpoints()).hasSize(2);
        // Should be sorted alphabetically
        assertThat(service.endpoints()).containsExactlyInAnyOrder(
                EndpointInfo.of("*", "/debug/foo", "b", SerializationFormat.THRIFT_TEXT,
                                EnumSet.of(SerializationFormat.THRIFT_TEXT)),
                EndpointInfo.of("*", "/foo", "a", SerializationFormat.THRIFT_BINARY,
                                EnumSet.of(SerializationFormat.THRIFT_BINARY)));

        final Map<String, FunctionInfo> functions = service.functions();
        assertThat(functions).hasSize(5);

        final FunctionInfo bar1 = functions.get("bar1");
        assertThat(bar1.parameters()).isEmpty();
        assertThat(bar1.returnType()).isEqualTo(TypeInfo.VOID);
        assertThat(bar1.exceptions()).hasSize(1);
        assertThat(bar1.sampleJsonRequest()).isEmpty();

        final TypeInfo string = TypeInfo.of(ValueType.STRING, false);
        final FunctionInfo bar2 = functions.get("bar2");
        assertThat(bar2.parameters()).isEmpty();
        assertThat(bar2.returnType()).isEqualTo(string);
        assertThat(bar2.exceptions()).hasSize(1);
        assertThat(bar2.sampleJsonRequest()).isEmpty();

        final StructInfo foo = StructInfo.of(new StructMetaData(TType.STRUCT, FooStruct.class));
        final FunctionInfo bar3 = functions.get("bar3");
        assertThat(bar3.parameters()).containsExactly(
                FieldInfo.of("intVal", RequirementType.DEFAULT, TypeInfo.of(ValueType.I32, false)),
                FieldInfo.of("foo", RequirementType.DEFAULT, foo));
        assertThat(bar3.returnType()).isEqualTo(foo);
        assertThat(bar3.exceptions()).hasSize(1);
        assertThatJson(bar3.sampleJsonRequest()).isEqualTo("{\"intVal\": 10}");

        final FunctionInfo bar4 = functions.get("bar4");
        assertThat(bar4.parameters()).containsExactly(
                FieldInfo.of("foos", RequirementType.DEFAULT, ListInfo.of(foo)));
        assertThat(bar4.returnType()).isEqualTo(ListInfo.of(foo));
        assertThat(bar4.exceptions()).hasSize(1);
        assertThat(bar4.sampleJsonRequest()).isEmpty();

        final FunctionInfo bar5 = functions.get("bar5");
        assertThat(bar5.parameters()).containsExactly(
                FieldInfo.of("foos", RequirementType.DEFAULT, MapInfo.of(string, foo)));
        assertThat(bar5.returnType()).isEqualTo(MapInfo.of(string, foo));
        assertThat(bar5.exceptions()).hasSize(1);
        assertThat(bar5.sampleJsonRequest()).isEmpty();

        final String sampleHttpHeaders = service.sampleHttpHeaders();
        assertThat(sampleHttpHeaders).isNotNull();
        assertThatJson(sampleHttpHeaders).isEqualTo("{ \"foobar\": \"barbaz\" }");
    }
}
