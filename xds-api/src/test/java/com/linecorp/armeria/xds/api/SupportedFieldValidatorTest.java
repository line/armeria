/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.xds.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.protobuf.Any;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Duration;
import com.google.protobuf.UInt32Value;

import com.linecorp.armeria.xds.api.testing.TestCluster;
import com.linecorp.armeria.xds.api.testing.TestDiscoveryType;
import com.linecorp.armeria.xds.api.testing.TestEdsConfig;
import com.linecorp.armeria.xds.api.testing.TestOutlierDetection;
import com.linecorp.armeria.xds.validator.XdsValidationException;

class SupportedFieldValidatorTest {

    @Test
    void allSupportedFields() {
        final List<String> violations = new ArrayList<>();
        final SupportedFieldValidator validator =
                SupportedFieldValidator.of((descriptor, path, value) -> violations.add(path));

        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setType(TestDiscoveryType.STATIC)
                                               .build();
        validator.validate(cluster);
        assertThat(violations).isEmpty();
    }

    @Test
    void unsupportedScalarField() {
        final List<String> violations = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final SupportedFieldValidator validator =
                SupportedFieldValidator.of((descriptor, path, value) -> {
                    violations.add(path);
                    values.add(value);
                });

        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setUnsupportedField("bad")
                                               .build();
        validator.validate(cluster);
        assertThat(violations).containsExactly("$.unsupportedField");
        assertThat(values).containsExactly("bad");
    }

    @Test
    void unsupportedMessageField() {
        final List<String> violations = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final SupportedFieldValidator validator =
                SupportedFieldValidator.of((descriptor, path, value) -> {
                    violations.add(path);
                    values.add(value);
                });

        final TestOutlierDetection outlier = TestOutlierDetection.newBuilder()
                                                                  .setConsecutiveErrors(5)
                                                                  .build();
        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setOutlierDetection(outlier)
                                               .build();
        validator.validate(cluster);
        assertThat(violations).containsExactly("$.outlierDetection");
        assertThat(values).containsExactly(outlier);
    }

    @Test
    void recursiveNestedUnsupported() {
        final List<String> violations = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final SupportedFieldValidator validator =
                SupportedFieldValidator.of((descriptor, path, value) -> {
                    violations.add(path);
                    values.add(value);
                });

        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setEdsConfig(
                                                       TestEdsConfig.newBuilder()
                                                                    .setServiceName("svc")
                                                                    .setUnsupportedNested("bad")
                                                                    .build())
                                               .build();
        validator.validate(cluster);
        assertThat(violations).containsExactly("$.edsConfig.unsupportedNested");
        assertThat(values).containsExactly("bad");
    }

    @Test
    void unsupportedEnumValue() {
        final List<String> violations = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final SupportedFieldValidator validator =
                SupportedFieldValidator.of((descriptor, path, value) -> {
                    violations.add(path);
                    values.add(value);
                });

        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setType(TestDiscoveryType.LOGICAL_DNS)
                                               .build();
        validator.validate(cluster);
        assertThat(violations).containsExactly("$.type");
        assertThat(values).hasSize(1);
        assertThat(((EnumValueDescriptor) values.get(0)).getName()).isEqualTo("LOGICAL_DNS");
    }

    @Test
    void rejectHandler() {
        final SupportedFieldValidator validator = SupportedFieldValidator.of(
                UnsupportedFieldHandler.reject());

        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setUnsupportedField("bad")
                                               .build();
        assertThatThrownBy(() -> validator.validate(cluster))
                .isInstanceOf(XdsValidationException.class)
                .hasMessageContaining("$.unsupportedField");
    }

    @Test
    void ignoreHandler() {
        final SupportedFieldValidator validator = SupportedFieldValidator.of(
                UnsupportedFieldHandler.ignore());

        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setUnsupportedField("bad")
                                               .build();
        // Should not throw
        validator.validate(cluster);
    }

    @Test
    void fullyUnannotatedMessage() {
        final List<String> violations = new ArrayList<>();
        final SupportedFieldValidator validator =
                SupportedFieldValidator.of((descriptor, path, value) -> violations.add(path));

        final TestOutlierDetection outlier = TestOutlierDetection.newBuilder()
                                                                  .setConsecutiveErrors(5)
                                                                  .build();
        validator.validate(outlier);
        assertThat(violations).containsExactly("$.consecutiveErrors");
    }

    @Test
    void noopValidatorDoesNothing() {
        final SupportedFieldValidator validator = SupportedFieldValidator.noop();
        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setUnsupportedField("bad")
                                               .build();
        // Should not throw - noop ignores everything
        validator.validate(cluster);
    }

    @Test
    void andThenComposition() {
        final List<String> first = new ArrayList<>();
        final List<String> second = new ArrayList<>();
        final UnsupportedFieldHandler composed =
                ((UnsupportedFieldHandler) (descriptor, path, value) -> first.add(path))
                        .andThen((descriptor, path, value) -> second.add(path));
        final SupportedFieldValidator validator = SupportedFieldValidator.of(composed);

        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setUnsupportedField("bad")
                                               .build();
        validator.validate(cluster);
        assertThat(first).containsExactly("$.unsupportedField");
        assertThat(second).containsExactly("$.unsupportedField");
    }

    @Test
    void rejectFailsFast() {
        final UnsupportedFieldHandler failFast = UnsupportedFieldHandler.reject();
        final SupportedFieldValidator validator = SupportedFieldValidator.of(failFast);

        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setUnsupportedField("bad")
                                               .setOutlierDetection(
                                                       TestOutlierDetection.newBuilder()
                                                                           .setConsecutiveErrors(5)
                                                                           .build())
                                               .build();
        assertThatThrownBy(() -> validator.validate(cluster))
                .isInstanceOf(XdsValidationException.class);
    }

    @Test
    void ignoreEarlyExit() {
        // Validate that ignore() handler causes early exit (no recursion)
        // This is a behavior test — the ignore handler should skip validation entirely
        final SupportedFieldValidator validator = SupportedFieldValidator.of(
                UnsupportedFieldHandler.ignore());
        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setUnsupportedField("bad")
                                               .setOutlierDetection(
                                                       TestOutlierDetection.newBuilder()
                                                                           .setConsecutiveErrors(5)
                                                                           .build())
                                               .build();
        // Should not throw — ignore skips all validation
        validator.validate(cluster);
    }

    @Test
    void unsupportedEnumValueInMapField() {
        final List<String> violations = new ArrayList<>();
        final List<Object> values = new ArrayList<>();
        final SupportedFieldValidator validator =
                SupportedFieldValidator.of((descriptor, path, value) -> {
                    violations.add(path);
                    values.add(value);
                });

        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .putTypeMap("a", TestDiscoveryType.STATIC)
                                               .putTypeMap("b", TestDiscoveryType.LOGICAL_DNS)
                                               .build();
        validator.validate(cluster);
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).endsWith(".value");
        assertThat(((EnumValueDescriptor) values.get(0)).getName()).isEqualTo("LOGICAL_DNS");
    }

    @Test
    void supportedEnumValueInMapField() {
        final List<String> violations = new ArrayList<>();
        final SupportedFieldValidator validator =
                SupportedFieldValidator.of((descriptor, path, value) -> violations.add(path));

        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .putTypeMap("a", TestDiscoveryType.STATIC)
                                               .putTypeMap("b", TestDiscoveryType.EDS)
                                               .build();
        validator.validate(cluster);
        assertThat(violations).isEmpty();
    }

    @Test
    void externalTypesDoNotProduceFalsePositives() {
        final List<String> violations = new ArrayList<>();
        final SupportedFieldValidator validator =
                SupportedFieldValidator.of((descriptor, path, value) -> violations.add(path));

        final TestCluster cluster = TestCluster.newBuilder()
                                               .setName("test")
                                               .setTypedConfig(Any.pack(
                                                       UInt32Value.of(42)))
                                               .setMaxRequests(UInt32Value.of(100))
                                               .setTimeout(Duration.newBuilder()
                                                                    .setSeconds(30)
                                                                    .build())
                                               .build();
        validator.validate(cluster);
        // External types (Any, UInt32Value, Duration) should not be recursed into,
        // so their internal fields (type_url, value, seconds, nanos) must not appear.
        assertThat(violations).isEmpty();
    }
}
