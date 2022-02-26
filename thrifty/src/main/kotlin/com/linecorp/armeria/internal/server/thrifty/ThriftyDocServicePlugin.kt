package com.linecorp.armeria.internal.server.thrifty

import com.linecorp.armeria.server.Service
import com.linecorp.armeria.server.ServiceConfig
import com.linecorp.armeria.server.docs.DocServiceFilter
import com.linecorp.armeria.server.docs.DocServicePlugin
import com.linecorp.armeria.server.docs.ServiceSpecification

class ThriftyDocServicePlugin : DocServicePlugin {
    override fun name(): String = "thrifty"

    override fun supportedServiceTypes(): MutableSet<Class<out Service<*, *>>> {
        TODO("Not yet implemented")
    }

    override fun generateSpecification(
        serviceConfigs: MutableSet<ServiceConfig>,
        filter: DocServiceFilter
    ): ServiceSpecification {
        TODO("Not yet implemented")
    }
}