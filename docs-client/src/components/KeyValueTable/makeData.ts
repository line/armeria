export interface Data {
  fieldName: string;
  fieldValue: string;
}

const range = (len: number) => {
  return Array.apply(null, Array(len))
    .fill(0)
    .map((_, i) => i);
};

const newRow: () => Data = () => {
  return {
    fieldName: '',
    fieldValue: '',
  };
};

export default function makeData(...lens: number[]) {
  const makeDataLevel = (depth = 0) => {
    const len = lens[depth];
    return range(len).map(() => {
      return {
        ...newRow(),
      };
    });
  };

  return makeDataLevel();
}
