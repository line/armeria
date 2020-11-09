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

import Autocomplete from '@material-ui/lab/Autocomplete';
import { createStyles, makeStyles, Theme } from '@material-ui/core/styles';
import TextField from '@material-ui/core/TextField';
import React, { ChangeEvent, useCallback } from 'react';

import { Specification } from '../../lib/specification';
import { SelectOption } from '../../lib/types';

type Option = SelectOption & { group: string };

const useStyles = makeStyles((theme: Theme) =>
  createStyles({
    root: {
      margin: theme.spacing(1),
      marginLeft: theme.spacing(3),
      width: 800,
      backgroundColor: theme.palette.primary.light,
      borderRadius: theme.spacing(2),
    },
    inputRoot: {
      padding: theme.spacing(0.25, 1.25),
    },
    input: {
      color: 'rgba(0, 0, 0, 0.62)',
      '&::placeholder': {
        opacity: 0.62,
      },
    },
    popupIndicator: {
      marginRight: theme.spacing(0.5),
    },
  }),
);

function getOptions(specification: Specification): Option[] {
  const options: Option[] = [];

  for (const service of specification.getServices()) {
    for (const method of service.methods) {
      options.push({
        group: 'Services',
        label: `${service.name}#${method.name}|${method.httpMethod}`,
        value: `/methods/${service.name}/${method.name}/${method.httpMethod}`,
      });
    }
  }

  for (const enm of specification.getEnums()) {
    options.push({
      group: 'Enums',
      label: `${enm.name}`,
      value: `/enums/${enm.name}/`,
    });
  }

  for (const struct of specification.getStructs()) {
    options.push({
      group: 'Structs',
      label: `${struct.name}`,
      value: `/structs/${struct.name}/`,
    });
  }

  for (const exception of specification.getExceptions()) {
    options.push({
      group: 'Exceptions',
      label: `${exception.name}`,
      value: `/structs/${exception.name}/`,
    });
  }

  return options;
}

interface GotoSelectProps {
  specification: Specification;
  navigateTo: (url: string) => void;
}

const GotoSelect: React.FunctionComponent<GotoSelectProps> = ({
  specification,
  navigateTo,
}) => {
  const classes = useStyles();

  const handleSelection = useCallback(
    (_: ChangeEvent<{}>, option: Option | null): void => {
      if (option) {
        navigateTo(option.value);
      }
    },
    [navigateTo],
  );

  return (
    <div className={classes.root}>
      <Autocomplete
        autoHighlight
        blurOnSelect
        disableClearable
        disablePortal
        classes={{
          inputRoot: classes.inputRoot,
          input: classes.input,
          popupIndicator: classes.popupIndicator,
        }}
        options={getOptions(specification)}
        getOptionLabel={(option) => option.label}
        groupBy={(option) => option.group}
        noOptionsText="No results"
        onChange={handleSelection}
        renderInput={(params) => (
          <TextField
            {...params}
            autoFocus
            placeholder="Go to ..."
            InputProps={{
              ...params.InputProps,
              disableUnderline: true,
            }}
          />
        )}
      />
    </div>
  );
};

export default React.memo(GotoSelect);
