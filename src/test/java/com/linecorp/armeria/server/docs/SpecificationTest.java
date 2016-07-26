/*
 * Copyright 2016 LINE Corporation
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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;

import org.junit.Test;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.VirtualHostBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.service.test.thrift.main.FooService;
import com.linecorp.armeria.service.test.thrift.main.HelloService;

public class SpecificationTest {

    @Test
    public void servicesTest() throws Exception {
        final Specification specification = Specification.forServiceConfigs(
                Arrays.asList(
                        new ServiceConfig(
                                new VirtualHostBuilder().build(),
                                PathMapping.ofExact("/hello"),
                                THttpService.of(mock(HelloService.AsyncIface.class))),
                        new ServiceConfig(
                                new VirtualHostBuilder().build(),
                                PathMapping.ofExact("/foo"),
                                THttpService.ofFormats(mock(FooService.AsyncIface.class),
                                                       SerializationFormat.THRIFT_COMPACT))),
                Collections.emptyMap(),
                Collections.emptyMap());

        final Map<String, ServiceInfo> services = specification.services();
        assertThat(services.size(), is(2));

        assertThat(services.containsKey(HelloService.class.getName()), is(true));
        assertThat(services.get(HelloService.class.getName()).endpoints(),
                   contains(EndpointInfo.of("*", "/hello", SerializationFormat.THRIFT_BINARY,
                                            SerializationFormat.ofThrift())));

        assertThat(services.containsKey(FooService.class.getName()), is(true));
        assertThat(services.get(FooService.class.getName()).endpoints(),
                   contains(EndpointInfo.of("*", "/foo", SerializationFormat.THRIFT_COMPACT,
                                            EnumSet.of(SerializationFormat.THRIFT_COMPACT))));
    }
}
