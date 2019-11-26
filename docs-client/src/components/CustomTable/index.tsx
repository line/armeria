import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
import React from 'react';
import { useTable } from 'react-table';

// Set our editable cell renderer as the default Cell renderer
// const defaultColumn = CustomTableCell;
const CustomTable: ({
  columns,
  data,
  updateCell,
  cellRenderer,
  removeRow,
}: {
  columns: any;
  data: any;
  updateCell: any;
  cellRenderer: any;
  removeRow: any;
}) => any = ({ columns, data, updateCell, cellRenderer, removeRow }) => {
  // Set our editable cell renderer as the default Cell renderer
  const defaultColumn = {
    Cell: cellRenderer,
  };

  const {
    getTableProps,
    getTableBodyProps,
    headerGroups,
    rows,
    prepareRow,
  } = useTable({
    defaultColumn,
    columns,
    data,
    updateCell, // custom user props
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
            const isLastRow = i1 === rows.length - 1;
            prepareRow(row);
            return (
              <TableRow key={i1} {...row.getRowProps()}>
                {row.cells.map((cell, i2) => {
                  const isLastCell = i2 === row.cells.length - 1;
                  return (
                    <TableCell key={i2} {...cell.getCellProps()}>
                      {cell.render('Cell', {
                        isLastRow,
                      })}
                      {!isLastRow && isLastCell && (
                        <button onClick={() => removeRow(i1)} />
                      )}
                    </TableCell>
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
