package com.linecorp.armeria.server.http.dynamic;

/**
 * Parameter entry, which will be used to invoke the {@link DynamicHttpFunctionEntry}.
 *
 * @see DynamicHttpServiceBuilder#addMappings(Object)
 */
final class ParameterEntry {
    private Class<?> type;
    private String name;

    ParameterEntry(Class<?> type, String name) {
        this.type = type;
        this.name = name;
    }

    Class<?> getType() {
        return type;
    }

    String getName() {
        return name;
    }
}
