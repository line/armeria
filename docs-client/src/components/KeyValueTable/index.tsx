import React, { Dispatch, SetStateAction, useContext } from 'react';

import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import {
  CreateDefaultRow,
  Row,
  ValueListContext,
} from '../KeyValueEditor/valueListContext';
import { KeyValueTableRow } from '../KeyValueRow';

interface Props {
  defaultKeyValueList?: Row[];
  keyName?: string;
  valueName?: string;
}

const KeyValueTable: React.FunctionComponent<Props> = ({
  defaultKeyValueList,
  keyName,
  valueName,
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
      rowList.filter((_, i) => i !== index).map((v, i) => ({ ...v, index: i })),
    );
  };

  const onRowChange = (index: number, name: string, value: string) => {
    if (!rowList) return;

    const newRowList = rowList.map((row, i) =>
      i === index ? { ...row, [name]: value } : row,
    );
    if (index === rowList.length - 1) {
      newRowList.push(CreateDefaultRow());
    }

    setRowList(newRowList);
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
