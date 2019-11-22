import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import React from 'react';
import { useTable } from 'react-table';
import { KeyValueTableCellRenderer } from '../KeyValueTableCellRenderer';

// Set our editable cell renderer as the default Cell renderer
const defaultColumn = {
  Cell: KeyValueTableCellRenderer,
};

// Set our editable cell renderer as the default Cell renderer
// const defaultColumn = CustomTableCell;
const CustomTable: ({
  columns,
  data,
  updateData,
}: {
  columns: any;
  data: any;
  updateData: any;
}) => any = ({ columns, data, updateData }) => {
  const {
    getTableProps,
    getTableBodyProps,
    headerGroups,
    rows,
    prepareRow,
  } = useTable({
    defaultColumn,
    updateData,
    columns,
    data,
  });

  return (
    <Table {...getTableProps}>
      <TableHead>
        {headerGroups.map((headerGroup, i1) => (
          <TableRow key={i1} {...headerGroup.getHeaderGroupProps()}>
            {headerGroup.headers.map((column, i2) => (
              <TableCell key={i2} {...column.getHeaderProps()}>
                {column.render('Header')}
              </TableCell>
            ))}
          </TableRow>
        ))}
      </TableHead>
      <TableBody {...getTableBodyProps()}>
        {rows &&
          rows.map((row, i1) => {
            prepareRow(row);
            return (
              <TableRow key={i1} {...row.getRowProps()}>
                {row.cells.map((cell, i2) => {
                  return (
                    <td key={i2} {...cell.getCellProps()}>
                      {cell.render('Cell')}
                    </td>
                  );
                })}
              </TableRow>
            );
          })}
      </TableBody>
    </Table>
  );
};

export default React.memo(CustomTable);
