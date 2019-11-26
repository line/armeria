import { createStyles, makeStyles, Theme } from '@material-ui/core';
import React from 'react';

interface Props {
  cell: {
    value: string;
  };
  row: {
    index: number;
  };
  column: {
    id: number;
  };
  updateCell: (...args: any) => void;
  isLastRow: boolean;
}

const useStyles = makeStyles((theme: Theme) =>
  createStyles({
    input: {
      padding: theme.spacing(1),
      border: 'none',
      borderBottom: `1px solid ${theme.palette.primary.dark}`,
      background: '#fff',
      width: '100%',
    },
  }),
);

export const KeyValueTableCellRenderer: React.FC<Props> = ({
  cell: { value: initialValue },
  row: { index },
  column: { id },
  updateCell,
  isLastRow,
}) => {
  const classes = useStyles();
  // We need to keep and update the state of the cell normally
  const [value, setValue] = React.useState();

  const onChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setValue(e.target.value);
  };
  // We'll only update the external data when the input is blurred
  const onBlur = () => {
    updateCell(index, id, value, isLastRow);
  };

  // If the initialValue is changed externall, sync it up with our state
  React.useEffect(() => {
    setValue(initialValue);
  }, [initialValue]);

  // we'll use native input because input of material-ui doesn't have onBlur property
  return (
    <input
      className={classes.input}
      value={value || ''}
      onChange={onChange}
      onBlur={onBlur}
    />
  );
};
