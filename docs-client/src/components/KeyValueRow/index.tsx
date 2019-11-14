import { IconButton, Input, makeStyles } from '@material-ui/core';
import DeleteIcon from '@material-ui/core/SvgIcon/SvgIcon';
import TableCell from '@material-ui/core/TableCell';
import TableRow from '@material-ui/core/TableRow';
import React from 'react';
import { KeyValueTableCell } from '../KeyValueCell';
import { ACTION } from '../KeyValueEditor/valueListContext';

interface Props {
  columnIdx: number;
  isRemovable: boolean;
}

const useStyles = makeStyles((theme) => ({
  floatButton: {
    position: 'absolute',
    right: `${theme.spacing(2)}px`,
  },
}));

export const KeyValueTableRow: React.FunctionComponent<Props> = ({
  rowIdx,
  columnIdx,
  isRemovable,
}) => {
  const classes = useStyles();

  const onRemove = () => {
    onUpdate(ACTION.REMOVE_ROW, {
      columnIdx,
      rowIdx,
    });
  };

  return (
    <TableRow key={rowIdx}>
      <KeyValueTableCell onUpdate={onUpdate} />
      <TableCell>
        {isRemovable && (
          <IconButton
            onClick={() => onRemove()}
            className={classes.floatButton}
            aria-label="delete"
          >
            <DeleteIcon />
          </IconButton>
        )}
      </TableCell>
    </TableRow>
  );
};
