package com.linecorp.armeria.internal.spring;

import org.springframework.context.SmartLifecycle;

import com.linecorp.armeria.server.Server;

/**
 * A {@link SmartLifecycle} for start and stop control of Armeria {@link Server}.
 */
public interface ArmeriaServerSmartLifecycle extends SmartLifecycle {}
