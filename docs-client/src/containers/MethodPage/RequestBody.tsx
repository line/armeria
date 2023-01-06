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
import TextField from '@material-ui/core/TextField';
import Typography from '@material-ui/core/Typography';
import React, { ChangeEvent } from 'react';

import Select from '@material-ui/core/Select';
import MenuItem from '@material-ui/core/MenuItem';
import { Tooltip } from '@material-ui/core';
import jsonPrettify from '../../lib/json-prettify';
import { truncate } from '../../lib/strings';

const jsonPlaceHolder = jsonPrettify('{"foo":"bar"}');

interface Props {
  exampleRequests: string[];
  onSelectedRequestBodyChange: (e: ChangeEvent<{ value: unknown }>) => void;
  requestBodyOpen: boolean;
  requestBody: string;
  onEditRequestBodyClick: React.Dispatch<unknown>;
  onDebugFormChange: (value: string) => void;
}

const RequestBody: React.FunctionComponent<Props> = (props) => (
  <>
    <Typography variant="body2" paragraph />
    <Button color="secondary" onClick={props.onEditRequestBodyClick}>
      Request body
    </Button>
    {props.requestBodyOpen && (
      <>
        {props.exampleRequests.length > 0 && (
          <>
            <Typography variant="body2" paragraph />
            <Select
              fullWidth
              displayEmpty
              value=""
              renderValue={() => 'Select example requests...'}
              onChange={props.onSelectedRequestBodyChange}
            >
              {props.exampleRequests.map((body) => (
                <Tooltip title={body} placement="right">
                  <MenuItem key={body} value={body}>
                    {truncate(body, 30)}
                  </MenuItem>
                </Tooltip>
              ))}
            </Select>
          </>
        )}
        <Typography variant="body2" paragraph />
        <TextField
          multiline
          fullWidth
          rows={15}
          value={props.requestBody}
          placeholder={jsonPlaceHolder}
          onChange={(e) => {
            return props.onDebugFormChange(e.target.value as string);
          }}
          inputProps={{
            className: 'code',
          }}
        />
      </>
    )}
  </>
);

export default React.memo(RequestBody);
