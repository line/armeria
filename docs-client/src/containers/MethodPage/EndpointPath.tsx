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
import Dropdown, { Option } from 'react-dropdown';

const endpointPathPlaceHolder = '/foo/bar';

interface Props {
  editable: boolean;
  isAnnotatedService: boolean;
  endpointPathOpen: boolean;
  examplePaths: Option[];
  additionalPath: string;
  onSelectedPathChange: (selectedPath: Option) => void;
  onEditEndpointPathClick: React.Dispatch<unknown>;
  onPathFormChange: (e: ChangeEvent<HTMLInputElement>) => void;
}

const EndpointPath: React.FunctionComponent<Props> = (props) => (
  <>
    <Typography variant="body2" paragraph />
    <Button color="secondary" onClick={props.onEditEndpointPathClick}>
      Endpoint path
    </Button>
    <Typography variant="body2" paragraph />
    {props.endpointPathOpen && (
      <>
        {props.isAnnotatedService ? (
          <>
            {props.examplePaths.length > 0 && (
              <>
                <Typography variant="body2" paragraph />
                <Dropdown
                  placeholder="Select an example path..."
                  options={props.examplePaths}
                  onChange={props.onSelectedPathChange}
                />
              </>
            )}
            <Typography variant="body2" paragraph />
            <TextField
              fullWidth
              value={props.additionalPath}
              placeholder={endpointPathPlaceHolder}
              onChange={props.onPathFormChange}
              inputProps={{
                readOnly: !props.editable,
                className: 'code',
              }}
            />
          </>
        ) : (
          <>
            <Typography variant="body2" paragraph />
            <Dropdown
              options={props.examplePaths}
              onChange={props.onSelectedPathChange}
              value={props.additionalPath}
            />
          </>
        )}
      </>
    )}
  </>
);

export default React.memo(EndpointPath);
