import type { Dayjs } from 'dayjs';

import dayjs from 'dayjs';
import { describe, expect, it } from 'vitest';

import { createDateRangeFilterCodec } from './date-range-codec';

describe('createDateRangeFilterCodec', () => {
  const codec = createDateRangeFilterCodec('createTime');

  it('encodes a date range without mutating form values', () => {
    const values = {
      createTime: [dayjs('2026-08-01'), dayjs('2026-08-11')],
      name: 'operator',
    };

    expect(codec.encode(values)).toEqual({
      endTime: '2026-08-11',
      name: 'operator',
      startTime: '2026-08-01',
    });
    expect(values.createTime).toHaveLength(2);
  });

  it('removes stale range fields when the range is cleared', () => {
    expect(
      codec.encode({
        createTime: null,
        endTime: '2026-08-11',
        startTime: '2026-08-01',
      }),
    ).toEqual({});
  });

  it('decodes submitted dates for the range picker without mutating input', () => {
    const values = {
      endTime: '2026-08-11',
      name: 'operator',
      startTime: '2026-08-01',
    };

    const decoded = codec.decode(values);

    expect(decoded.name).toBe('operator');
    expect(
      decoded.createTime?.map((value: Dayjs | null) =>
        value?.format('YYYY-MM-DD'),
      ),
    ).toEqual(['2026-08-01', '2026-08-11']);
    expect(values).toEqual({
      endTime: '2026-08-11',
      name: 'operator',
      startTime: '2026-08-01',
    });
  });
});
