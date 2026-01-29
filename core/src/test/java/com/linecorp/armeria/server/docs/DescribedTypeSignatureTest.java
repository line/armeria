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

package com.linecorp.armeria.server.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DescribedTypeSignatureTest {

    @Test
    void basicProperties() {
        final TypeSignature typeSignature = TypeSignature.ofStruct(IllegalArgumentException.class);
        final DescribedTypeSignature info = DescribedTypeSignature.of(typeSignature);

        assertThat(info.typeSignature()).isEqualTo(typeSignature);
        assertThat(info.descriptionInfo()).isEqualTo(DescriptionInfo.empty());
    }

    @Test
    void withDescriptionInfo() {
        final TypeSignature typeSignature = TypeSignature.ofStruct(IllegalStateException.class);
        final DescribedTypeSignature info = DescribedTypeSignature.of(typeSignature);
        final DescriptionInfo descriptionInfo = DescriptionInfo.of("This exception is thrown");

        final DescribedTypeSignature infoWithDescription = info.withDescriptionInfo(descriptionInfo);

        assertThat(infoWithDescription.typeSignature()).isEqualTo(typeSignature);
        assertThat(infoWithDescription.descriptionInfo()).isEqualTo(descriptionInfo);
        // Original should not be modified
        assertThat(info.descriptionInfo()).isEqualTo(DescriptionInfo.empty());
    }

    @Test
    void withDescriptionInfoReturnsSameInstanceWhenUnchanged() {
        final TypeSignature typeSignature = TypeSignature.ofStruct(RuntimeException.class);
        final DescriptionInfo descriptionInfo = DescriptionInfo.of("Test description");
        final DescribedTypeSignature info = DescribedTypeSignature.of(typeSignature, descriptionInfo);

        final DescribedTypeSignature same = info.withDescriptionInfo(descriptionInfo);
        assertThat(same).isSameAs(info);
    }

    @Test
    void factoryMethodWithDescription() {
        final TypeSignature typeSignature = TypeSignature.ofStruct(Exception.class);
        final DescriptionInfo descriptionInfo = DescriptionInfo.of("Exception description");
        final DescribedTypeSignature info = DescribedTypeSignature.of(typeSignature, descriptionInfo);

        assertThat(info.typeSignature()).isEqualTo(typeSignature);
        assertThat(info.descriptionInfo()).isEqualTo(descriptionInfo);
    }

    @Test
    void equalsAndHashCode() {
        final TypeSignature typeSignature1 = TypeSignature.ofStruct(IllegalArgumentException.class);
        final TypeSignature typeSignature2 = TypeSignature.ofStruct(IllegalStateException.class);
        final DescriptionInfo descriptionInfo = DescriptionInfo.of("description");

        final DescribedTypeSignature info1 = DescribedTypeSignature.of(typeSignature1);
        final DescribedTypeSignature info2 = DescribedTypeSignature.of(typeSignature1);
        final DescribedTypeSignature info3 = DescribedTypeSignature.of(typeSignature2);
        final DescribedTypeSignature info4 = DescribedTypeSignature.of(typeSignature1, descriptionInfo);

        // Same type signature and description
        assertThat(info1).isEqualTo(info2);
        assertThat(info1.hashCode()).isEqualTo(info2.hashCode());

        // Different type signature
        assertThat(info1).isNotEqualTo(info3);

        // Different description
        assertThat(info1).isNotEqualTo(info4);
    }

    @Test
    void testToString() {
        final TypeSignature typeSignature = TypeSignature.ofStruct(Exception.class);
        final DescribedTypeSignature info = DescribedTypeSignature.of(typeSignature);

        final String toString = info.toString();
        assertThat(toString).contains("DescribedTypeSignature");
        assertThat(toString).contains("typeSignature");
        assertThat(toString).contains("descriptionInfo");
    }
}
