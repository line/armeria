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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.Map;

import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TType;
import org.junit.Test;

import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.FooStruct;

public class ServiceInfoTest {

    @Test
    public void fooServiceTest() throws Exception {
        final ServiceInfo service = ServiceInfo.of(FooService.class);

        final Map<String, FunctionInfo> functions = service.functions();
        assertThat(functions.size(), is(5));

        final FunctionInfo bar1 = functions.get("bar1");
        assertThat(bar1.parameters().isEmpty(), is(true));
        assertThat(bar1.returnType(), is(TypeInfo.VOID));
        assertThat(bar1.exceptions().size(), is(1));

        final TypeInfo string = TypeInfo.of(ValueType.STRING, false);
        final FunctionInfo bar2 = functions.get("bar2");
        assertThat(bar2.parameters().isEmpty(), is(true));
        assertThat(bar2.returnType(), is(string));
        assertThat(bar2.exceptions().size(), is(1));

        final StructInfo foo = StructInfo.of(new StructMetaData(TType.STRUCT, FooStruct.class));
        final FunctionInfo bar3 = functions.get("bar3");
        assertThat(bar3.parameters().size(), is(2));
        assertThat(bar3.parameters().get(0),
                   is(FieldInfo.of("intVal", RequirementType.DEFAULT, TypeInfo.of(ValueType.I32, false))));
        assertThat(bar3.parameters().get(1), is(FieldInfo.of("foo", RequirementType.DEFAULT, foo)));
        assertThat(bar3.returnType(), is(foo));
        assertThat(bar3.exceptions().size(), is(1));

        final FunctionInfo bar4 = functions.get("bar4");
        assertThat(bar4.parameters().size(), is(1));
        assertThat(bar4.parameters().get(0),
                   is(FieldInfo.of("foos", RequirementType.DEFAULT, ListInfo.of(foo))));
        assertThat(bar4.returnType(), is(ListInfo.of(foo)));
        assertThat(bar4.exceptions().size(), is(1));

        final FunctionInfo bar5 = functions.get("bar5");
        assertThat(bar5.parameters().size(), is(1));
        assertThat(bar5.parameters().get(0),
                   is(FieldInfo.of("foos", RequirementType.DEFAULT, MapInfo.of(string, foo))));
        assertThat(bar5.returnType(), is(MapInfo.of(string, foo)));
        assertThat(bar5.exceptions().size(), is(1));
    }
}
