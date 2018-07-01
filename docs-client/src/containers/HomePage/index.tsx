/*
 * Copyright 2018 LINE Corporation
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

import Typography from '@material-ui/core/Typography';
import React from 'react';

export default class HomePage extends React.PureComponent {
  public render() {
    return (
      <>
        <Typography variant="title" paragraph>
          Welcome to the Armeria documentation service
        </Typography>
        <Typography variant="body1">
          This page provides the information about the RPC services and its
          related data types in this server.
        </Typography>
        <Typography variant="body1">
          Please start to navigate via the sidebar on the left or the menu
          button on the top right.
        </Typography>
      </>
    );
  }
}
