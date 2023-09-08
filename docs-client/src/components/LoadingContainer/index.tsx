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

import React, { ReactElement } from 'react';
import Grid from '@material-ui/core/Grid';
import { CircularProgress } from '@material-ui/core';
import { Alert } from '@material-ui/lab';
import { SpecLoadingStatus } from '../../lib/types';

interface LoadingContainerProps {
  children: ReactElement;
  status: SpecLoadingStatus;
  failureMessage: string;
}

const LoadingContainer: React.FunctionComponent<LoadingContainerProps> = ({
  children,
  status,
  failureMessage,
}) => {
  if (status === SpecLoadingStatus.INITIALIZED) {
    return (
      <Grid container alignItems="center" justifyContent="center">
        <Grid item>
          <CircularProgress />
        </Grid>
      </Grid>
    );
  }
  if (status === SpecLoadingStatus.FAILED) {
    return <Alert severity="warning">{failureMessage}</Alert>;
  }
  return children;
};

export default React.memo(LoadingContainer);
