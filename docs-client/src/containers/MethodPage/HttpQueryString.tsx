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
import MenuItem from '@material-ui/core/MenuItem';
import Select from '@material-ui/core/Select';
import TextField from '@material-ui/core/TextField';
import Typography from '@material-ui/core/Typography';
import React, { ChangeEvent } from 'react';

import { SelectOption } from '../../lib/types';

const queryPlaceHolder = 'foo=bar&baz=qux';

interface Props {
  additionalQueriesOpen: boolean;
  exampleQueries: SelectOption[];
  additionalQueries: string;
  onSelectedQueriesChange: (e: ChangeEvent<{ value: unknown }>) => void;
  onEditHttpQueriesClick: React.Dispatch<unknown>;
  onQueriesFormChange: (e: ChangeEvent<HTMLInputElement>) => void;
}

const HttpQueryString: React.FunctionComponent<Props> = (props) => (
  <>
    <Typography variant="body2" paragraph />
    <Button color="secondary" onClick={props.onEditHttpQueriesClick}>
      HTTP query string
    </Button>
    <Typography variant="body2" paragraph />
    {props.additionalQueriesOpen && (
      <>
        {props.exampleQueries.length > 0 && (
          <>
            <Typography variant="body2" paragraph />
            <Select
              fullWidth
              displayEmpty
              value=""
              renderValue={() => 'Select example queries...'}
              onChange={props.onSelectedQueriesChange}
            >
              {props.exampleQueries.map((query) => (
                <MenuItem key={query.value} value={query.value}>
                  {query.label}
                </MenuItem>
              ))}
            </Select>
          </>
        )}
        <Typography variant="body2" paragraph />
        <TextField
          fullWidth
          value={props.additionalQueries}
          placeholder={queryPlaceHolder}
          onChange={props.onQueriesFormChange}
          inputProps={{
            className: 'code',
          }}
        />
      </>
    )}
  </>
);

export default React.memo(HttpQueryString);
