import { Input } from '@material-ui/core';
import React, { Dispatch, SetStateAction, useContext } from 'react';

import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import { Row, ValueListContext } from '../KeyValueEditor/valueListContext';

interface RowProps {
  row: Row;
  onRowChange: (index: number, key: string, value: string) => void;
}

const KeyValueTableRow: React.FunctionComponent<RowProps> = ({
  row,
  onRowChange,
}) => {
  const onChange = (rkey: string, rvalue: string) => {
    onRowChange(row.index, rkey, rvalue);
  };

  return (
    <TableRow key={row.index}>
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
      </TableCell>
    </TableRow>
  );
};

interface KeyValueTableProps {
  defaultKeyValueList?: Row[];
}

const KeyValueTable: React.FunctionComponent<KeyValueTableProps> = ({}) => {
  const resultArr:
    | [Row[], Dispatch<SetStateAction<Row[]>>]
    | undefined = useContext(ValueListContext);

  if (!resultArr) throw new Error('KeyValueTable : RowList가 없습니다.');

  const [rowList, setRowList] = resultArr;

  const onRowChange = (index: number, name: string, value: string) => {
    if (rowList) {
      setRowList(
        rowList.map((row, i) =>
          i === index ? { ...row, [name]: value } : row,
        ),
      );
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
              row={{
                index: i,
                key: v.key,
                value: v.value,
              }}
              onRowChange={onRowChange}
            />
          ))}
      </TableBody>
    </Table>
  );
};

export default React.memo(KeyValueTable);
