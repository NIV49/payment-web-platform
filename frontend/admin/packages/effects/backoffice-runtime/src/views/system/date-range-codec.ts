import { formatDate } from '@vben/utils';

import dayjs from 'dayjs';

type DateRangeValue = [dayjs.Dayjs | null, dayjs.Dayjs | null];

export function createDateRangeFilterCodec(fieldName: string) {
  return {
    decode(values: Readonly<Record<string, any>>) {
      const { endTime, startTime, ...formValues } = values;
      const range: DateRangeValue | undefined =
        startTime || endTime
          ? [
              startTime ? dayjs(startTime) : null,
              endTime ? dayjs(endTime) : null,
            ]
          : undefined;
      return { ...formValues, [fieldName]: range };
    },
    encode(values: Readonly<Record<string, any>>) {
      const {
        [fieldName]: range,
        endTime: _endTime,
        startTime: _startTime,
        ...submitValues
      } = values;
      if (!Array.isArray(range)) return submitValues;

      const [startTime, endTime] = range;
      return {
        ...submitValues,
        endTime: endTime ? formatDate(endTime) : undefined,
        startTime: startTime ? formatDate(startTime) : undefined,
      };
    },
  };
}
