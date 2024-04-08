package com.linecorp.armeria.common.reactor3;


import io.micrometer.context.ContextRegistry;
import reactor.core.publisher.Hooks;

public class RequestContextPropagationHook {

    private static boolean enabled;

    public static synchronized void enable() {
        if (enabled) {
            return;
        }
        ContextRegistry.getInstance().registerThreadLocalAccessor(RequestContextAccessor.getInstance());
        Hooks.enableAutomaticContextPropagation();

        enabled = true;
    }

    public static boolean isEnable() {
        return enabled;
    }

    public static synchronized void disable() {
        if (!enabled) {
            return;
        }

        Hooks.disableAutomaticContextPropagation();
        enabled = false;
    }
}
