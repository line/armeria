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

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.linecorp.armeria.service.test.thrift.main.FooServiceException;

public class ExceptionInfoTest {

    @Test
    public void testOf() throws Exception {
        final List<FieldInfo> fields = new ArrayList<>(1);
        fields.add(FieldInfo.of("stringVal", RequirementType.DEFAULT, new TypeInfo(ValueType.STRING, false)));

        final ExceptionInfo exception = ExceptionInfo.of(FooServiceException.class);
        assertThat(exception, is(ExceptionInfo.of(FooServiceException.class.getName(), fields)));
    }
}
