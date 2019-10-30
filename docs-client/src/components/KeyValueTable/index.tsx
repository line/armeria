import { IconButton, Input, makeStyles } from '@material-ui/core';
import React, { Dispatch, SetStateAction, useContext } from 'react';

import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import DeleteIcon from '@material-ui/icons/Delete';
import { Row, ValueListContext } from '../KeyValueEditor/valueListContext';

interface RowProps {
  row: Row;
  index : number;
  onRowChange: (index: number, key: string, value: string) => void;
  onRowRemove: (index: number) => void;
}

const useStyles = makeStyles((theme) => ({
  floatButton: {
    position: 'absolute',
    right: `${theme.spacing(2)}px`,
  },
}));

const KeyValueTableRow: React.FunctionComponent<RowProps> = ({
  row,
  index,
  onRowChange,
  onRowRemove,
}) => {
  const classes = useStyles();
  const onChange = (index : number, key: string, value: string) => {
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
          onChange={(e) => onChange(index,'key', e.target.value)}
        />
      </TableCell>
      <TableCell>
        <Input
          defaultValue={row.value}
          onChange={(e) => onChange(index,'value', e.target.value)}
        />
        <IconButton
          onClick={() => onRemove()}
          className={classes.floatButton}
          aria-label="delete"
        >
          <DeleteIcon />
        </IconButton>
      </TableCell>
    </TableRow>
  );
};

interface KeyValueTableProps {
  defaultKeyValueList?: Row[];
}

const KeyValueTable: React.FunctionComponent<KeyValueTableProps> = ({
  defaultKeyValueList,
}) => {
  const resultArr:
    | [Row[], Dispatch<SetStateAction<Row[]>>]
    | undefined = useContext(ValueListContext);

  if (!resultArr) throw new Error("KeyValueTable : There's no RowList");

  const [rowList, setRowList] = resultArr;
  if (defaultKeyValueList) setRowList(defaultKeyValueList);

  const onRowRemove = (index: number) => {
    if (rowList.length === 1) return;
    setRowList(
      rowList
        .filter((_, i) => i !== index)
        .map((v, i) => ({ ...v, index: i })),
    );
  };

  const onRowChange = (index: number, name: string, value: string) => {
    if (rowList) {
      const newRowList = rowList.map((row, i) =>
        i === index ? { ...row, [name]: value } : row,
      );
      if (index === rowList.length - 1) {
        newRowList.push({ key: '', value: '' });
      }

      setRowList(newRowList);
    }
  };

  // tslint:disable-next-line
  console.log(rowList);
  return (
    <Table>
      <TableHead>
        <TableRow>
          <TableCell>KEY</TableCell>
          <TableCell>VALUE</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {rowList &&
          rowList.map((v, i) => (
            <KeyValueTableRow
              key={i}
              index={i}
              row={{
                key: v.key,
                value: v.value,
              }}
              onRowChange={onRowChange}
              onRowRemove={onRowRemove}
            />
          ))}
      </TableBody>
    </Table>
  );
};

export default React.memo(KeyValueTable);
