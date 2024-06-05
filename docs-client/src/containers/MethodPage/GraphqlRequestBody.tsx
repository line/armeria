/*
 * Copyright 2022 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

import React, {
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useState,
} from 'react';
import Typography from '@material-ui/core/Typography';
import Button from '@material-ui/core/Button';

import {
  buildClientSchema,
  GraphQLSchema,
  getIntrospectionQuery,
} from 'graphql';
import TextField from '@material-ui/core/TextField';
import Editor, { useMonaco, loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-graphql';
import { jsonPrettify } from '../../lib/json-util';
import { docServiceDebug } from '../../lib/header-provider';

// Required for graphQL plugin to load properly.
loader.config({ monaco });

const jsonPlaceHolder = jsonPrettify('{"foo":"bar"}');

interface Props {
  requestBodyOpen: boolean;
  requestBody: string;
  onEditRequestBodyClick: React.Dispatch<unknown>;
  onDebugFormChange: (value: string) => void;
  schemaUrlPath: string;
}

const toggle = (prev: boolean, override: unknown) => {
  if (typeof override === 'boolean') {
    return override;
  }
  return !prev;
};

const parseJson = (s: string) => {
  let parsedJson;
  try {
    parsedJson = JSON.parse(s);
  } catch (e) {
    // ignored
  }
  return parsedJson;
};

const GraphqlRequestBody: React.FunctionComponent<Props> = ({
  requestBodyOpen,
  requestBody,
  onEditRequestBodyClick,
  onDebugFormChange,
  schemaUrlPath,
}) => {
  const [queryOpen, toggleQueryOpen] = useReducer(toggle, true);
  const [variablesOpen, toggleVariablesOpen] = useReducer(toggle, false);

  const [query, setQuery] = useState('');
  const [variables, setVariables] = useState({});
  const [variablesText, setVariablesText] = useState('');
  const [schema, setSchema] = useState<GraphQLSchema | undefined>();

  const monacoEditor = useMonaco();

  useMemo(() => {
    // @ts-ignore
    monacoEditor?.languages?.graphql?.api.setSchemaConfig([
      {
        schema,
        fileMatch: ['*'],
        uri: '*',
      },
    ]);
  }, [monacoEditor, schema]);

  useEffect(() => {
    (async () => {
      const headers: any = {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      };
      if (process.env.WEBPACK_DEV === 'true') {
        headers[docServiceDebug] = 'true';
      }
      const httpResponse = await fetch(schemaUrlPath, {
        method: 'POST',
        headers,

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
      if (typeof result !== 'string' && 'data' in result) {
        setSchema(buildClientSchema(result.data));
      }
    })();
  }, [schemaUrlPath]);

  useEffect(() => {
    if (query !== '' || variablesText !== '') {
      return;
    }

    const parsedJson = parseJson(requestBody);
    if (parsedJson === undefined) {
      return;
    }

    setQuery(parsedJson.query);
    if (typeof parsedJson.variables === 'object') {
      setVariablesText(JSON.stringify(parsedJson.variables));
      toggleVariablesOpen(true);
    }
  }, [requestBody, query, variablesText]);

  useEffect(() => {
    onDebugFormChange(
      JSON.stringify({
        query,
        variables,
      }),
    );
  }, [onDebugFormChange, query, variables]);

  useEffect(() => {
    const parsed = parseJson(variablesText);
    if (parsed === undefined) {
      return;
    }
    setVariables(parsed);
  }, [variablesText]);

  const onQueryFromChange = useCallback((value) => {
    setQuery(value);
  }, []);

  const onVariablesTextFromChange = useCallback((value) => {
    setVariablesText(value);
  }, []);

  return (
    <>
      <Typography variant="body2" paragraph />
      <Button color="secondary" onClick={onEditRequestBodyClick}>
        Graphql Request body
      </Button>
      {requestBodyOpen && (
        <>
          <Typography variant="body2" paragraph />
          <Button size="small" color="secondary" onClick={toggleQueryOpen}>
            # Query
          </Button>
          {queryOpen && (
            <Editor
              height="30vh"
              language="graphql"
              theme="vs-light"
              value={query}
              options={{
                minimap: { enabled: false },
                fontSize: 14,
              }}
              onChange={(val) => val && onQueryFromChange(val)}
            />
          )}
          <Typography variant="body2" paragraph />
          <Button size="small" color="secondary" onClick={toggleVariablesOpen}>
            # Query Variables
          </Button>
          {variablesOpen && (
            <TextField
              multiline
              fullWidth
              minRows={5}
              value={variablesText}
              placeholder={jsonPlaceHolder}
              onChange={(e) => {
                return onVariablesTextFromChange(e.target.value);
              }}
              inputProps={{
                className: 'code',
              }}
            />
          )}
        </>
      )}
    </>
  );
};

export default React.memo(GraphqlRequestBody);
