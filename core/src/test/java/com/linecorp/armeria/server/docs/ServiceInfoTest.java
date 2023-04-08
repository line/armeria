/*
 *  Copyright 2019 LINE Corporation
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

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

class ServiceInfoTest {

    private static MethodInfo createMethodInfo(String methodName, HttpMethod method,
                                               String endpointPathMapping) {
        final EndpointInfo endpoint = EndpointInfo.builder("*", endpointPathMapping)
                .availableMimeTypes(MediaType.JSON_UTF_8).build();
        return new MethodInfo("", methodName, 0, TypeSignature.ofBase("T"), ImmutableList.of(),
                              ImmutableList.of(), ImmutableList.of(endpoint), method, DescriptionInfo.empty());
    }

    @Test
    void testCollectMethodsWithDifferentMethods() {
        final MethodInfo barMethodInfo = createMethodInfo("bar", HttpMethod.GET, "exact:/bar");
        final MethodInfo fooMethodInfo = createMethodInfo("foo", HttpMethod.GET, "exact:/foo");
        final List<MethodInfo> inputMethodInfos = ImmutableList.of(barMethodInfo, fooMethodInfo);
        assertThat(ServiceInfo.mergeEndpoints(inputMethodInfos)).isEqualTo(inputMethodInfos);
    }

    @Test
    void testCollectMethodGrouping() {

        final MethodInfo barGet1 = createMethodInfo("bar", HttpMethod.GET, "exact:/bar1");
        final MethodInfo barGet2 = createMethodInfo("bar", HttpMethod.GET, "exact:/bar2");
        final MethodInfo barPost3 = createMethodInfo("bar", HttpMethod.POST, "exact:/bar3");
        final MethodInfo barPost4 = createMethodInfo("bar", HttpMethod.POST, "exact:/bar4");
        final MethodInfo fooGet1 = createMethodInfo("foo", HttpMethod.GET, "exact:/foo1");
        final MethodInfo fooGet2 = createMethodInfo("foo", HttpMethod.GET, "exact:/foo2");
        final MethodInfo fooPost3 = createMethodInfo("foo", HttpMethod.POST, "exact:/foo3");
        final MethodInfo fooPost4 = createMethodInfo("foo", HttpMethod.POST, "exact:/foo4");

        final List<MethodInfo> inputMethodInfos = ImmutableList.of(barGet1, barGet2, barPost3, barPost4,
                                                                   fooGet1, fooGet2, fooPost3, fooPost4);
        final List<MethodInfo> collectMethods =
                ImmutableList.copyOf(ServiceInfo.mergeEndpoints(inputMethodInfos));

        assertThat(collectMethods).hasSize(4);

        final Function<MethodInfo, Set<String>> getPaths = methodInfo ->
                methodInfo.endpoints().stream().map(EndpointInfo::pathMapping).collect(Collectors.toSet());
        assertThat(getPaths.apply(collectMethods.get(0))).containsExactly("exact:/bar1", "exact:/bar2");
        assertThat(getPaths.apply(collectMethods.get(1))).containsExactly("exact:/bar3", "exact:/bar4");
        assertThat(getPaths.apply(collectMethods.get(2))).containsExactly("exact:/foo1", "exact:/foo2");
        assertThat(getPaths.apply(collectMethods.get(3))).containsExactly("exact:/foo3", "exact:/foo4");
    }
}
