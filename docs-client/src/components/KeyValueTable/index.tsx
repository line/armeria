import { IconButton, Input, makeStyles } from '@material-ui/core';
import React, { Dispatch, SetStateAction, useContext } from 'react';

import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import DeleteIcon from '@material-ui/icons/Clear';
import { RowListToStr, StrToRowList } from '../KeyValueEditor';
import {
  CreateDefaultRow,
  Row,
  ValueListContext,
} from '../KeyValueEditor/valueListContext';

interface RowProps {
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

const KeyValueTableRow: React.FunctionComponent<RowProps> = ({
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

interface KeyValueTableProps {
  defaultKeyValueListString?: string;
  keyName?: string;
  valueName?: string;
}

const KeyValueTable: React.FunctionComponent<KeyValueTableProps> = ({
  defaultKeyValueListString,
  keyName,
  valueName,
}) => {
  const resultArr:
    | [string, Dispatch<SetStateAction<string>>]
    | undefined = useContext(ValueListContext);

  if (!resultArr) throw new Error("KeyValueTable : There's no RowList");

  const [rowListString, setRowListString] = resultArr;
  const rowList = StrToRowList(rowListString);
  if (defaultKeyValueListString) setRowListString(defaultKeyValueListString);

  const onRowRemove = (index: number) => {
    if (rowList.length === 1) return;
    setRowListString(
      RowListToStr(
        rowList
          .filter((_, i) => i !== index)
          .map((v, i) => ({ ...v, index: i })),
      ),
    );
  };

  const onRowChange = (index: number, name: string, value: string) => {
    if (!rowListString) return;
    const newRowList = rowList.map((row, i) =>
      i === index ? { ...row, [name]: value } : row,
    );

    if (index === rowList.length - 1) {
      newRowList.push(CreateDefaultRow());
    }

    setRowListString(RowListToStr(newRowList));
  };

  return (
    <Table>
      <TableHead>
        <TableRow>
          <TableCell>{keyName}</TableCell>
          <TableCell>{valueName}</TableCell>
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
              isRemovable={Boolean(rowList.length - 1 !== i)}
            />
          ))}
      </TableBody>
    </Table>
  );
};

export default React.memo(KeyValueTable);
