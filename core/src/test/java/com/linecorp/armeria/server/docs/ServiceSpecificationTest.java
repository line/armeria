package com.linecorp.armeria.server.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

class ServiceSpecificationTest {

    @Test
    void preferAliasedStructInfo() {
        final StructInfo noAlias = new StructInfo("foo", ImmutableList.of());
        final StructInfo aliased = noAlias.withAlias("bar");
        ServiceSpecification specification =
                new ServiceSpecification(ImmutableList.of(), ImmutableList.of(),
                                         ImmutableList.of(noAlias, aliased), ImmutableList.of());
        assertThat(specification.structs()).containsExactly(aliased);

        specification = new ServiceSpecification(ImmutableList.of(), ImmutableList.of(),
                                                 ImmutableList.of(aliased, noAlias), ImmutableList.of());
        assertThat(specification.structs()).containsExactly(aliased);
    }
}
