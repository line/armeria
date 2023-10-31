package com.linecorp.armeria.server.graphql;

import graphql.ExecutionInput;
import graphql.ExecutionResult;

interface GraphqlExecutor {
    ExecutionResult executeGraphql(ExecutionInput executionInput);
}
