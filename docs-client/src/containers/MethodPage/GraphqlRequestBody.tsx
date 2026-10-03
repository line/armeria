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

import React, { useCallback, useEffect, useReducer, useRef } from 'react';
import Typography from '@material-ui/core/Typography';
import Button from '@material-ui/core/Button';

import TextField from '@material-ui/core/TextField';
import Editor, { useMonaco, loader, OnMount } from '@monaco-editor/react';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-graphql';
import { jsonPrettify } from '../../lib/json-util';
import { GraphqlEditorState } from './useGraphqlDebugState';

// Required for graphQL plugin to load properly.
loader.config({ monaco });

const jsonPlaceHolder = jsonPrettify('{"foo":"bar"}');

interface Props {
  requestBodyOpen: boolean;
  onEditRequestBodyClick: React.Dispatch<unknown>;
  methodId: string;
  editorState: GraphqlEditorState;
}

const toggle = (prev: boolean, override: unknown) => {
  if (typeof override === 'boolean') {
    return override;
  }
  return !prev;
};

const GraphqlRequestBody: React.FunctionComponent<Props> = ({
  requestBodyOpen,
  onEditRequestBodyClick,
  methodId,
  editorState,
}) => {
  const {
    schema,
    query,
    variablesText,
    stateMethodId,
    onQueryChange,
    onVariablesTextChange,
  } = editorState;
  const [queryOpen, toggleQueryOpen] = useReducer(toggle, true);
  const [variablesOpen, toggleVariablesOpen] = useReducer(toggle, false);
  const previousStateMethodId = useRef('');
  const monacoEditor = useMonaco();

  useEffect(() => {
    toggleQueryOpen(true);
    toggleVariablesOpen(false);
    previousStateMethodId.current = '';
  }, [methodId]);

  useEffect(() => {
    if (stateMethodId !== methodId) {
      return;
    }
    if (previousStateMethodId.current !== stateMethodId) {
      toggleVariablesOpen(variablesText !== '');
    }
    previousStateMethodId.current = stateMethodId;
  }, [methodId, stateMethodId, variablesText]);

  useEffect(() => {
    if (schema === undefined) {
      return;
    }
    // @ts-ignore
    monacoEditor?.languages?.graphql?.api.setSchemaConfig(
      schema
        ? [
            {
              schema,
              fileMatch: ['*'],
              uri: '*',
            },
          ]
        : [],
    );
  }, [monacoEditor, schema]);

  const onEditorMount = useCallback<OnMount>(
    (editor) => {
      if (editor.getValue() !== query) {
        editor.setValue(query);
      }
    },
    [query],
  );

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
          <div style={{ display: queryOpen ? 'block' : 'none' }}>
            <Editor
              height="30vh"
              keepCurrentModel
              language="graphql"
              path="inmemory://docs-client/graphql-request"
              theme="vs-light"
              value={query}
              options={{
                minimap: { enabled: false },
                fontSize: 14,
                occurrencesHighlight: 'off',
              }}
              onMount={onEditorMount}
              onChange={(val) => onQueryChange(val ?? '')}
            />
          </div>
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
                return onVariablesTextChange(e.target.value);
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
