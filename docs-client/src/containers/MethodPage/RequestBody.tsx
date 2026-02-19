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
import React, { ChangeEvent, useMemo } from 'react';

import Select from '@material-ui/core/Select';
import MenuItem from '@material-ui/core/MenuItem';
import { Tooltip } from '@material-ui/core';

import Editor, { loader, useMonaco } from '@monaco-editor/react';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';

import { truncate } from '../../lib/strings';
import { Method, ServiceType } from '../../lib/specification';

loader.config({ monaco });

interface Props {
  exampleRequests: string[];
  onSelectedRequestBodyChange: (e: ChangeEvent<{ value: unknown }>) => void;
  requestBodyOpen: boolean;
  requestBody: string;
  onEditRequestBodyClick: React.Dispatch<unknown>;
  onDebugFormChange: (value: string) => void;
  method: Method;
  serviceType: ServiceType;
  jsonSchemas: any;
}

const RequestBody: React.FunctionComponent<Props> = ({
  exampleRequests,
  onSelectedRequestBodyChange,
  requestBody,
  requestBodyOpen,
  onEditRequestBodyClick,
  onDebugFormChange,
  method,
  serviceType,
  jsonSchemas,
}) => {
  const monacoEditor = useMonaco();

  const supportsJsonSchema =
    serviceType === ServiceType.GRPC || serviceType === ServiceType.THRIFT;
  useMemo(() => {
    if (supportsJsonSchema) {
      // Find the method schema from the JSON Schema structure.
      // Structure: { $defs: { methods: { "method-name": { $id: "service/method/HTTP", ... } } } }
      let methodSchema: any = {};
      const methods = jsonSchemas?.$defs?.methods;
      const models = jsonSchemas?.$defs?.models;
      if (methods && models) {
        const found: any = Object.values(methods).find(
          (m: any) => m.$id === method.id,
        );
        if (found) {
          // Extract the request type schema directly for autocomplete
          // The method schema has { properties: { request: { $ref: "..." } } }
          // We want to use the referenced model as the root schema
          const requestProp = found.properties?.request;
          if (requestProp?.$ref) {
            // Extract model name from $ref (e.g., "#/$defs/models/X" -> "X")
            const modelName = requestProp.$ref.replace('#/$defs/models/', '');
            const requestModel = models[modelName];
            if (requestModel) {
              methodSchema = {
                ...requestModel,
                $defs: { models },
              };
            }
          } else {
            methodSchema = {
              ...found,
              $defs: { models },
            };
          }
        }
      }

      monacoEditor?.languages.json.jsonDefaults.setDiagnosticsOptions({
        validate: true,
        schemas: [
          {
            schema: methodSchema,
            fileMatch: ['*'],
            uri: '*',
          },
        ],
      });
    } else {
      monacoEditor?.languages.json.jsonDefaults.setDiagnosticsOptions({
        validate: false,
      });
    }
  }, [monacoEditor, jsonSchemas, method.id, supportsJsonSchema]);

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
            language={supportsJsonSchema ? 'json' : undefined}
            theme="vs-light"
            options={{
              minimap: { enabled: false },
              fontSize: 14,
            }}
            value={requestBody}
            onChange={(val) => val && onDebugFormChange(val)}
          />
        </>
      )}
    </>
  );
};

export default React.memo(RequestBody);
