/*
 * Copyright 2021 LINE Corporation
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

package server.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.common.thrift.ThriftServiceMetadata;
import com.linecorp.armeria.service.test.thrift.main.SayHelloService;

/**
 * Additional test for camel name support.
 */
public class ThriftServiceMetadataTest {

    @Test
    void testCamelName() {
        final ThriftServiceMetadata metadata = new ThriftServiceMetadata(SayHelloService.Iface.class);
        assertThat(metadata.function("say_hello")).isNotNull();
        assertThat(metadata.function("sayHello")).isNotNull();
    }
}
