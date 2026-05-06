/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.it.istio.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a test method to run inside a Kubernetes Job in the Istio-enabled K3s cluster
 * rather than locally. The local test body is skipped; the extension submits a Job,
 * waits for it, and propagates success or failure back to JUnit.
 *
 * <p>The enclosing test class must register {@link IstioClusterExtension}:
 * <pre>{@code
 * class MyTest {
 *     @RegisterExtension
 *     static IstioClusterExtension istio = new IstioClusterExtension();
 *
 *     @IstioPodTest
 *     void myTest() {
 *         // runs inside the K8s Job
 *     }
 * }
 * }</pre>
 *
 * <p>To use a custom {@link PodCustomizer}, specify the class:
 * <pre>{@code
 *     @IstioPodTest(podCustomizer = MyCustomizer.class)
 *     void myTest() { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Test
@ExtendWith(IstioTestExtension.class)
public @interface IstioPodTest {

    /**
     * The {@link PodCustomizer} class to use when creating the test pod.
     * The class must have a public no-arg constructor.
     * Defaults to {@link IstioPodCustomizer}.
     */
    Class<? extends PodCustomizer> podCustomizer() default IstioPodCustomizer.class;
}
