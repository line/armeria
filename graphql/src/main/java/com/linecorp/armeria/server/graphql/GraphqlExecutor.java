package com.linecorp.armeria.server.graphql;

import com.linecorp.armeria.server.ServiceRequestContext;
import graphql.ExecutionInput;
import graphql.ExecutionResult;

interface GraphqlExecutor {
    ExecutionResult executeGraphql(ServiceRequestContext ctx, ExecutionInput.Builder builder);
}
