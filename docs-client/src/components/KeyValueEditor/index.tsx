import { Input } from '@material-ui/core';
import React, { useState } from 'react';

import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
interface Row {
  index: number;
  key?: string;
  value?: string;
}

interface RowProps {
  value?: Row;
  id: number;
  onRowChange: (index: number, key: string, value: string) => void;
}

const useKeyValueList = (
  defaultRow: Row[] | undefined,
): {
  rowList: Row[] | undefined;
  onRowChange: (index: number, key: string, value: string) => void;
} => {
  const [rowList, setRowList] = useState(defaultRow);
  const onRowChange = (index: number, name: string, value: string) => {
    if (rowList) {
      setRowList(
        rowList.map((row, i) =>
          i === index ? { ...row, [name]: value } : row,
        ),
      );
    }
  };
  return {
    rowList,
    onRowChange,
  };
};

const KeyValueEditorRow: React.FunctionComponent<RowProps> = ({
  value,
  id,
  onRowChange,
}) => {
  const [row] = useState<Row>({ ...value, index: id });
  const onChange = (rkey: string, rvalue: string) => {
    onRowChange(id, rkey, rvalue);
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

interface KeyValueEditorProps {
  defaultKeyValueList?: Row[];
}

const KeyValueEditor: React.FunctionComponent<KeyValueEditorProps> = ({
  defaultKeyValueList,
}) => {
  const { rowList, onRowChange } = useKeyValueList(defaultKeyValueList);

  return (
    <Table>
      <TableHead>
        <TableRow>
          <TableCell>KEY</TableCell>
          <TableCell>VALUE</TableCell>
          <TableCell>DESCRIPTION</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {rowList &&
          rowList.map((v, i) => (
            <KeyValueEditorRow
              value={v}
              key={i}
              id={i}
              onRowChange={onRowChange}
            />
          ))}
      </TableBody>
    </Table>
  );
};

export default React.memo(KeyValueEditor);
