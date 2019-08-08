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

import MenuItem from '@material-ui/core/MenuItem';
import NoSsr from '@material-ui/core/NoSsr';
import Paper from '@material-ui/core/Paper';
import {
  createStyles,
  Theme,
  withStyles,
  WithStyles,
} from '@material-ui/core/styles';
import { emphasize } from '@material-ui/core/styles/colorManipulator';
import TextField, { BaseTextFieldProps } from '@material-ui/core/TextField';
import Typography from '@material-ui/core/Typography';
import React, { CSSProperties, HTMLAttributes } from 'react';
import Async from 'react-select/async';
import { ValueContainerProps } from 'react-select/src/components/containers';
import { ControlProps } from 'react-select/src/components/Control';
import { MenuProps, NoticeProps } from 'react-select/src/components/Menu';
import { OptionProps } from 'react-select/src/components/Option';
import { PlaceholderProps } from 'react-select/src/components/Placeholder';
import { SingleValueProps } from 'react-select/src/components/SingleValue';
import { ValueType } from 'react-select/src/types';
import { Specification } from '../../lib/specification';

interface OptionType {
  label: string;
  value: string;
}

interface GroupType {
  label: string;
  options: OptionType[];
}

const styles = (theme: Theme) =>
  createStyles({
    root: {
      position: 'relative',
      margin: `${theme.spacing(1)}px`,
      marginLeft: `${theme.spacing(3)}px`,
      minWidth: 300,
      width: 800,
      backgroundColor: theme.palette.primary.light,
      borderRadius: `${theme.spacing(2)}px`,
    },
    input: {
      display: 'flex',
      marginLeft: `${theme.spacing(1)}px`,
    },
    valueContainer: {
      display: 'flex',
      flexWrap: 'wrap',
      flex: 1,
      alignItems: 'center',
    },
    chip: {
      margin: `${theme.spacing(1 / 2)}px ${theme.spacing(1 / 4)}px`,
    },
    chipFocused: {
      backgroundColor: emphasize(
        theme.palette.type === 'light'
          ? theme.palette.grey[300]
          : theme.palette.grey[700],
        0.08,
      ),
    },
    noOptionsMessage: {
      padding: `${theme.spacing(1)}px ${theme.spacing(2)}px`,
    },
    singleValue: {
      position: 'relative',
      fontSize: 16,
    },
    placeholder: {
      position: 'absolute',
      marginLeft: `${theme.spacing(1)}px`,
      left: 2,
    },
    paper: {
      position: 'absolute',
      zIndex: 1,
      marginTop: theme.spacing(1),
      left: 0,
      right: 0,
      flex: 1,
    },
    divider: {
      height: theme.spacing(2),
    },
  });

function NoOptionsMessage(props: NoticeProps<OptionType>) {
  return (
    <Typography
      color="textSecondary"
      className={props.selectProps.classes.noOptionsMessage}
      {...props.innerProps}
    >
      No Results
    </Typography>
  );
}

type InputComponentProps = Pick<BaseTextFieldProps, 'inputRef'> &
  HTMLAttributes<HTMLDivElement>;

function inputComponent({ inputRef, ...props }: InputComponentProps) {
  return <div ref={inputRef} {...props} />;
}

function Control(props: ControlProps<OptionType>) {
  const {
    children,
    innerProps,
    innerRef,
    selectProps: { classes, TextFieldProps },
  } = props;

  return (
    <TextField
      fullWidth
      InputProps={{
        inputComponent,
        inputProps: {
          children,
          className: classes.input,
          ref: innerRef,
          ...innerProps,
        },
        disableUnderline: true,
      }}
      {...TextFieldProps}
    />
  );
}

function Option(props: OptionProps<OptionType>) {
  return (
    <MenuItem
      buttonRef={props.innerRef}
      selected={props.isFocused}
      component="div"
      style={{
        fontWeight: props.isSelected ? 500 : 400,
        width: 800,
      }}
      {...props.innerProps}
    >
      {props.children}
    </MenuItem>
  );
}

function Placeholder(props: PlaceholderProps<OptionType>) {
  return (
    <Typography
      color="textSecondary"
      className={props.selectProps.classes.placeholder}
      {...props.innerProps}
    >
      Go to ...
    </Typography>
  );
}

function SingleValue(props: SingleValueProps<OptionType>) {
  return (
    <Typography
      className={props.selectProps.classes.singleValue}
      noWrap={true}
      {...props.innerProps}
    >
      {props.children}
    </Typography>
  );
}

function ValueContainer(props: ValueContainerProps<OptionType>) {
  return (
    <div className={props.selectProps.classes.valueContainer}>
      {props.children}
    </div>
  );
}

function Menu(props: MenuProps<OptionType>) {
  return (
    <Paper
      square
      className={props.selectProps.classes.paper}
      {...props.innerProps}
    >
      {props.children}
    </Paper>
  );
}

const components = {
  Control,
  Menu,
  NoOptionsMessage,
  Option,
  Placeholder,
  SingleValue,
  ValueContainer,
};

/**
 * Convert doc specification into suggestion model.
 *
 * @param specification DocService Specification which is delivered from Armeria server.
 * @param limit Limit of suggestion model to avoid slow rendering & memory issue.
 * @param predicate Predicate to filter suggestion model from user typing input.
 */
function makeSuggestions(
  specification: Specification,
  limit: number,
  predicate: (n: string) => boolean,
): GroupType[] {
  const suggestions: GroupType[] = [];
  let remain = limit;

  function predicateWithLimit(option: OptionType) {
    if (predicate(option.label) && remain > 0) {
      remain -= 1;
      return true;
    }
    return false;
  }

  if (specification.getServices().length > 0 && remain > 0) {
    suggestions.push({
      label: 'Services',
      options: specification.getServices().flatMap((service) => {
        return service.methods
          .map((method) => {
            return {
              label: `${service.name}#${method.name}|${method.httpMethod}`,
              value: `/methods/${service.name}/${method.name}/${method.httpMethod}`,
            };
          })
          .filter(predicateWithLimit);
      }),
    });
  }

  if (specification.getEnums().length > 0 && remain > 0) {
    suggestions.push({
      label: 'Enums',
      options: specification
        .getEnums()
        .map((enm) => {
          return {
            label: `${enm.name}`,
            value: `/enums/${enm.name}/`,
          };
        })
        .filter(predicateWithLimit),
    });
  }

  if (specification.getStructs().length > 0 && remain > 0) {
    suggestions.push({
      label: 'Structs',
      options: specification
        .getStructs()
        .map((struct) => {
          return {
            label: `${struct.name}`,
            value: `/structs/${struct.name}/`,
          };
        })
        .filter(predicateWithLimit),
    });
  }

  if (specification.getExceptions().length > 0 && remain > 0) {
    suggestions.push({
      label: 'Exceptions',
      options: specification
        .getExceptions()
        .map((exception) => {
          return {
            label: `${exception.name}`,
            value: `/structs/${exception.name}/`,
          };
        })
        .filter(predicateWithLimit),
    });
  }

  return suggestions;
}

interface GotoSelectProps extends WithStyles<typeof styles, true> {
  specification: Specification;
  navigateTo: (url: string) => void;
}

const DEFAULT_SUGGESTION_SIZE = 100;
const FILTERED_SUGGESTION_SIZE = 20;

class GotoSelect extends React.Component<GotoSelectProps> {
  public render() {
    const { classes, theme, specification, navigateTo } = this.props;

    const selectStyles = {
      input: (base: CSSProperties) => ({
        ...base,
        color: theme.palette.text.secondary,
        '& input': {
          font: 'inherit',
        },
      }),
    };

    function handleSelection(option: ValueType<OptionType>): void {
      if (option) {
        navigateTo((option as OptionType).value);
      }
    }

    function filterSuggestion(
      inputValue: string,
      callback: (n: GroupType[]) => void,
    ): void {
      callback(
        makeSuggestions(specification, FILTERED_SUGGESTION_SIZE, (suggestion) =>
          suggestion.toLowerCase().includes(inputValue.toLowerCase()),
        ),
      );
    }

    return (
      <div className={classes.root}>
        <NoSsr>
          <Async
            autoFocus={true}
            classes={classes}
            styles={selectStyles}
            inputId="go-to-select"
            // The type parameter of Async seems to incorrectly use the same type for options and onChange
            // @ts-ignore
            defaultOptions={makeSuggestions(
              specification,
              DEFAULT_SUGGESTION_SIZE,
              () => true,
            )}
            // The type parameter of Async seems to incorrectly use the same type for options and onChange
            // @ts-ignore
            loadOptions={filterSuggestion}
            components={components}
            onChange={handleSelection}
          />
        </NoSsr>
      </div>
    );
  }
}

export default withStyles(styles, { withTheme: true })(GotoSelect);
