/*
 * Copyright 2020 LINE Corporation
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
import Checkbox from '@material-ui/core/Checkbox';
import FormControlLabel from '@material-ui/core/FormControlLabel';
import MenuItem from '@material-ui/core/MenuItem';
import Select from '@material-ui/core/Select';
import TextField from '@material-ui/core/TextField';
import Typography from '@material-ui/core/Typography';
import React, { ChangeEvent } from 'react';

import jsonPrettify from '../../lib/json-prettify';
import { SelectOption } from '../../lib/types';

const jsonPlaceHolder = jsonPrettify('{"foo":"bar"}');

interface Props {
  exampleHeaders: SelectOption[];
  additionalHeadersOpen: boolean;
  additionalHeaders: string;
  stickyHeaders: boolean;
  onEditHttpHeadersClick: React.Dispatch<unknown>;
  onSelectedHeadersChange: (e: ChangeEvent<{ value: unknown }>) => void;
  onHeadersFormChange: (e: ChangeEvent<HTMLInputElement>) => void;
  onStickyHeadersChange: React.Dispatch<unknown>;
}

const HttpHeaders: React.FunctionComponent<Props> = (props) => (
  <>
    <Typography variant="body2" paragraph />
    <Button color="secondary" onClick={props.onEditHttpHeadersClick}>
      HTTP headers
    </Button>
    {props.additionalHeadersOpen && (
      <>
        {props.exampleHeaders.length > 0 && (
          <>
            <Typography variant="body2" paragraph />
            <Select
              fullWidth
              displayEmpty
              value=""
              renderValue={() => 'Select example headers...'}
              onChange={props.onSelectedHeadersChange}
            >
              {props.exampleHeaders.map((header) => (
                <MenuItem key={header.value} value={header.value}>
                  {header.label}
                </MenuItem>
              ))}
            </Select>
          </>
        )}
        <Typography variant="body2" paragraph />
        <TextField
          multiline
          fullWidth
          rows={8}
          value={props.additionalHeaders}
          placeholder={jsonPlaceHolder}
          onChange={props.onHeadersFormChange}
          inputProps={{
            className: 'code',
          }}
        />
        <Typography variant="body2" paragraph />
        <FormControlLabel
          control={
            <Checkbox
              checked={props.stickyHeaders}
              onChange={props.onStickyHeadersChange}
            />
          }
          label="Use these HTTP headers for all functions."
        />
      </>
    )}
  </>
);

export default React.memo(HttpHeaders);
