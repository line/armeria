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

const endpointPathPlaceHolder = '/foo/bar';

interface Props {
  endpointPathOpen: boolean;
  endpointPath: string;
  onEditEndpointPathClick: () => void;
  onEndpointPathChange: (e: ChangeEvent<HTMLInputElement>) => void;
}

const EndpointPath: React.SFC<Props> = (props) => {
  return (
    <>
      <Typography variant="body2" paragraph />
      <Button color="secondary" onClick={props.onEditEndpointPathClick}>
        Endpoint path
      </Button>
      <Typography variant="body2" paragraph />
      {props.endpointPathOpen && (
        <>
          <TextField
            multiline
            fullWidth
            rows={1}
            value={props.endpointPath}
            placeholder={endpointPathPlaceHolder}
            onChange={props.onEndpointPathChange}
            inputProps={{
              className: 'code',
            }}
          />
        </>
      )}
    </>
  );
};

export default EndpointPath;
