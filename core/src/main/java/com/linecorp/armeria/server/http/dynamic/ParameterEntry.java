package com.linecorp.armeria.server.http.dynamic;

import javax.annotation.Nullable;

/**
 * Parameter entry, which will be used to invoke the {@link DynamicHttpFunctionEntry}.
 *
 * @see DynamicHttpServiceBuilder#addMappings(Object)
 */
final class ParameterEntry {
    private Class<?> type;
    private String name;

    ParameterEntry(Class<?> type, @Nullable String name) {
        this.type = type;
        this.name = name;
    }

    Class<?> getType() {
        return type;
    }

    @Nullable
    String getName() {
        return name;
    }

    boolean isPathParam() {
        return name != null;
    }
}
