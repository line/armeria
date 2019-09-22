import { Input, withStyles } from '@material-ui/core';
import { createStyles, Theme, WithStyles } from '@material-ui/core/styles';
import React, { CSSProperties } from 'react';

import Table from '@material-ui/core/Table';
import TableBody from '@material-ui/core/TableBody';
import TableCell from '@material-ui/core/TableCell';
import TableHead from '@material-ui/core/TableHead';
import TableRow from '@material-ui/core/TableRow';
interface ValueType {
  key: string;
  value: string;
  description: string;
}

const styles = (theme: Theme) =>
  createStyles({
    root: {
      width: '100%',
      marginTop: theme.spacing(3),
      overflowX: 'auto',
      // position: 'relative',
      // margin: `${theme.spacing(1)}px`,
      // marginLeft: `${theme.spacing(3)}px`,
      // minWidth: 300,
      // width: 800,
      // backgroundColor: theme.palette.primary.light,
      // borderRadius: `${theme.spacing(2)}px`,
    },
    table: {
      minWidth: 650,
    },
  });

interface KeyValueEditorProps extends WithStyles<typeof styles, true> {}
interface KeyValueEditorState extends WithStyles<typeof styles, true> {
  valueList: ValueType[];
}

class KeyValueEditor extends React.Component<
  KeyValueEditorProps,
  KeyValueEditorState
> {
  public handleUpdate(idx, targetKey, targetValue) {}

  public render() {
    const { classes } = this.props;
    const { valueList } = this.state;
    return (
      <Table className={classes.table}>
        <TableHead>
          <TableRow>
            <TableCell>KEY</TableCell>
            <TableCell>VALUE</TableCell>
            <TableCell>DESCRIPTION</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {valueList.map((v, i) => (
            <TableRow key={i}>
              <TableCell>
                <Input defaultValue={v.key} />
              </TableCell>
              <TableCell>
                <Input defaultValue={v.value} />
              </TableCell>
              <TableCell>
                <Input defaultValue={v.description} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    );
  }
}

export default withStyles(styles, { withTheme: true })(KeyValueEditor);
