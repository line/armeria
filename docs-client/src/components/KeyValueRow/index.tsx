import { IconButton, Input, makeStyles } from '@material-ui/core';
import DeleteIcon from '@material-ui/core/SvgIcon/SvgIcon';
import TableCell from '@material-ui/core/TableCell';
import TableRow from '@material-ui/core/TableRow';
import React from 'react';
import { Row } from '../KeyValueEditor/valueListContext';

interface Props {
  row: Row;
  index: number;
  onRowChange: (index: number, key: string, value: string) => void;
  onRowRemove: (index: number) => void;
  isRemovable: boolean;
}

const useStyles = makeStyles((theme) => ({
  floatButton: {
    position: 'absolute',
    right: `${theme.spacing(2)}px`,
  },
}));

export const KeyValueTableRow: React.FunctionComponent<Props> = ({
  row,
  index,
  onRowChange,
  onRowRemove,
  isRemovable,
}) => {
  const classes = useStyles();

  const onChange = (key: string, value: string) => {
    onRowChange(index, key, value);
  };

  const onRemove = () => {
    onRowRemove(index);
  };

  return (
    <TableRow key={index}>
      <TableCell>
        <Input
          value={row.key}
          onChange={(e) => onChange('key', e.target.value)}
        />
      </TableCell>
      <TableCell>
        <Input
          defaultValue={row.value}
          onChange={(e) => onChange('value', e.target.value)}
        />
        {isRemovable ? (
          <IconButton
            onClick={() => onRemove()}
            className={classes.floatButton}
            aria-label="delete"
          >
            <DeleteIcon />
          </IconButton>
        ) : (
          ''
        )}
      </TableCell>
    </TableRow>
  );
};
