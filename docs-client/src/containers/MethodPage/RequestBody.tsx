/*
 * Copyright 2019 LINE Corporation
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

import Button from '@material-ui/core/Button';
import Typography from '@material-ui/core/Typography';
import React, { ChangeEvent, useEffect } from 'react';

import Select from '@material-ui/core/Select';
import MenuItem from '@material-ui/core/MenuItem';
import { Tooltip } from '@material-ui/core';

import Editor, { useMonaco, loader } from '@monaco-editor/react';
// eslint-disable-next-line import/extensions
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api.js';

import jsonPrettify from '../../lib/json-prettify';
import { truncate } from '../../lib/strings';
import { Method } from '../../lib/specification';

loader.config({ monaco });

const jsonPlaceHolder = jsonPrettify('{"foo":"bar"}');

interface Props {
  exampleRequests: string[];
  onSelectedRequestBodyChange: (e: ChangeEvent<{ value: unknown }>) => void;
  requestBodyOpen: boolean;
  requestBody: string;
  onEditRequestBodyClick: React.Dispatch<unknown>;
  onDebugFormChange: (value: string) => void;
  method: Method;
  jsonSchemas: any[];
}

const RequestBody: React.FunctionComponent<Props> = ({
  exampleRequests,
  onSelectedRequestBodyChange,
  requestBody,
  requestBodyOpen,
  onEditRequestBodyClick,
  onDebugFormChange,
  jsonSchemas,
  method,
}) => {
  const monacoEditor = useMonaco();

  useEffect(() => {
    const schema = jsonSchemas.find((s: any) => s.$id === method.id) || {};

    monacoEditor?.languages.json.jsonDefaults.setDiagnosticsOptions({
      validate: true,
      schemas: [
        {
          schema,
          fileMatch: ['*'],
          uri: '*',
        },
      ],
    });
  }, [monacoEditor, jsonSchemas, method.id]);

  return (
    <>
      <Typography variant="body2" paragraph />
      <Button color="secondary" onClick={onEditRequestBodyClick}>
        Request body
      </Button>
      {requestBodyOpen && (
        <>
          {exampleRequests.length > 0 && (
            <>
              <Typography variant="body2" paragraph />
              <Select
                fullWidth
                displayEmpty
                value=""
                renderValue={() => 'Select example requests...'}
                onChange={onSelectedRequestBodyChange}
              >
                {exampleRequests.map((exampleRequestBody) => (
                  <MenuItem key={exampleRequestBody} value={exampleRequestBody}>
                    <Tooltip title={exampleRequestBody} placement="right">
                      <span>{truncate(exampleRequestBody, 30)}</span>
                    </Tooltip>
                  </MenuItem>
                ))}
              </Select>
            </>
          )}
          <Typography variant="body2" paragraph />
          <Editor
            height="30vh"
            language="json"
            theme="vs-light"
            value={requestBody}
            defaultValue={jsonPlaceHolder}
            onChange={(val) => val && onDebugFormChange(val)}
          />
        </>
      )}
    </>
  );
};

export default React.memo(RequestBody);
