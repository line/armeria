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

import {
  Dispatch,
  SetStateAction,
  useCallback,
  useEffect,
  useMemo,
  useState,
} from 'react';
import {
  buildClientSchema,
  getIntrospectionQuery,
  GraphQLSchema,
} from 'graphql';
import { docServiceDebug } from '../../lib/header-provider';
import { extractUrlPath, Method, ServiceType } from '../../lib/specification';

export interface GraphqlEditorState {
  schema: GraphQLSchema | null | undefined;
  query: string;
  variablesText: string;
  stateMethodId: string;
  onQueryChange: (value: string) => void;
  onVariablesTextChange: (value: string) => void;
}

interface Props {
  method: Method;
  serviceType: ServiceType;
  setRequestBody: Dispatch<SetStateAction<string>>;
}

const serializeGraphqlRequestBody = (
  query: string,
  variablesText: string,
): string => {
  let variables = {};
  if (variablesText.trim() !== '') {
    let parsed;
    try {
      parsed = JSON.parse(variablesText);
    } catch (error) {
      throw new Error(
        `Failed to parse a JSON object in the GraphQL variables:\n${error}`,
      );
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('The GraphQL variables must be a JSON object.');
    }
    variables = parsed;
  }
  return JSON.stringify({ query, variables });
};

const useGraphqlDebugState = ({
  method,
  serviceType,
  setRequestBody,
}: Props) => {
  const [query, setQuery] = useState('');
  const [variablesText, setVariablesText] = useState('');
  const [stateMethodId, setStateMethodId] = useState('');
  const [schema, setSchema] = useState<GraphQLSchema | null | undefined>();

  const schemaUrlPath =
    serviceType === ServiceType.GRAPHQL ? extractUrlPath(method) : undefined;

  useEffect(() => {
    if (!schemaUrlPath) {
      setSchema(undefined);
      return undefined;
    }

    const abortController = new AbortController();
    setSchema(null);
    (async () => {
      try {
        const headers: Record<string, string> = {
          'Content-Type': 'application/json',
          Accept: 'application/json',
        };
        if (process.env.WEBPACK_DEV === 'true') {
          headers[docServiceDebug] = 'true';
        }
        const httpResponse = await fetch(schemaUrlPath, {
          method: 'POST',
          headers,
          signal: abortController.signal,
          body: JSON.stringify({
            operationName: 'IntrospectionQuery',
            // See https://github.com/graphql/graphiql/blob/8ac05f8b141b6f5cb4449c62ad67a34115490ac8/packages/graphiql/src/utility/introspectionQueries.ts#L16...L22
            query: getIntrospectionQuery().replace(
              'subscriptionType { name }',
              '',
            ),
          }),
        });
        const result = await httpResponse.json();
        if (abortController.signal.aborted) {
          return;
        }
        if (typeof result !== 'string' && 'data' in result) {
          setSchema(buildClientSchema(result.data));
        } else {
          setSchema(null);
        }
      } catch {
        if (!abortController.signal.aborted) {
          setSchema(null);
        }
      }
    })();

    return () => abortController.abort();
  }, [schemaUrlPath]);

  const synchronizeWithRequestBody = useCallback(
    (body: string, methodId: string) => {
      if (body === '') {
        setQuery('');
        setVariablesText('');
        setStateMethodId(methodId);
        return;
      }

      try {
        const parsed = JSON.parse(body);
        if (!parsed || typeof parsed !== 'object') {
          setQuery('');
          setVariablesText('');
          setStateMethodId(methodId);
          return;
        }
        setQuery(typeof parsed.query === 'string' ? parsed.query : '');
        const variables =
          parsed.variables &&
          typeof parsed.variables === 'object' &&
          !Array.isArray(parsed.variables)
            ? parsed.variables
            : {};
        setVariablesText(
          Object.keys(variables).length > 0 ? JSON.stringify(variables) : '',
        );
        setStateMethodId(methodId);
      } catch {
        setQuery('');
        setVariablesText('');
        setStateMethodId(methodId);
      }
    },
    [],
  );

  const updateRequestBody = useCallback(
    (nextQuery: string, nextVariablesText: string) => {
      try {
        setRequestBody(
          serializeGraphqlRequestBody(nextQuery, nextVariablesText),
        );
      } catch {
        setRequestBody(nextVariablesText);
      }
    },
    [setRequestBody],
  );

  const onQueryChange = useCallback(
    (value: string) => {
      setStateMethodId(method.id);
      setQuery(value);
      updateRequestBody(value, variablesText);
    },
    [method.id, updateRequestBody, variablesText],
  );

  const onVariablesTextChange = useCallback(
    (value: string) => {
      setStateMethodId(method.id);
      setVariablesText(value);
      updateRequestBody(query, value);
    },
    [method.id, query, updateRequestBody],
  );

  const editorState = useMemo<GraphqlEditorState>(
    () => ({
      schema,
      query,
      variablesText,
      stateMethodId,
      onQueryChange,
      onVariablesTextChange,
    }),
    [
      onQueryChange,
      onVariablesTextChange,
      query,
      schema,
      stateMethodId,
      variablesText,
    ],
  );

  const serializeRequestBody = useCallback(
    () => serializeGraphqlRequestBody(query, variablesText),
    [query, variablesText],
  );

  return {
    editorState,
    serializeRequestBody,
    synchronizeWithRequestBody,
  };
};

export default useGraphqlDebugState;
