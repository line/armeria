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
package com.linecorp.armeria.client.endpoint.zookeeper;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

public class DefaultZkNodeValueConverterTest {
    @Rule
    public ExpectedException exceptionGrabber = ExpectedException.none();

    @Test
    public void convert() {
        ZkNodeValueConverter converter = new DefaultZkNodeValueConverter();
        converter.convert("localhost:8001,localhost:8002:2,192.abc.1.2".getBytes());
        assertThat(
                converter.convert("localhost:8001,localhost:8002:2,192.168.1.2".getBytes()),
                is(ImmutableList.of(Endpoint.of("localhost", 8001), Endpoint.of("localhost", 8002, 2),
                                    Endpoint.of("192.168.1.2"))));
        exceptionGrabber.expect(IllegalArgumentException.class);
        converter.convert("http://localhost:8001,localhost:8002:2,192.168.1.2".getBytes());
    }
}
