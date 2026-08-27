import { describe, expect, it } from 'vitest';

import {
  dictionaryDataRouteLocation,
  dictionaryTypeSelectOptions,
  resolveDictionaryTypeQuery,
} from './navigation';

describe('dictionary data route selection', () => {
  it('normalizes valid lowercase query values and fails closed on malformed input', () => {
    expect(resolveDictionaryTypeQuery('sys_user_sex')).toBe('SYS_USER_SEX');
    expect(resolveDictionaryTypeQuery('SYS_USER_SEX')).toBe('SYS_USER_SEX');
    expect(resolveDictionaryTypeQuery('bad-type')).toBeUndefined();
    expect(resolveDictionaryTypeQuery(['SYS_USER_SEX'])).toBeUndefined();
  });

  it('builds the single dictionary data landing route with a canonical query', () => {
    expect(dictionaryDataRouteLocation('sys_user_sex')).toEqual({
      name: 'SystemDictionaryDataIndex',
      query: { dictType: 'SYS_USER_SEX' },
    });
    expect(dictionaryDataRouteLocation(['SYS_USER_SEX'])).toBeUndefined();
  });

  it('builds stable selector options from the live type catalog', () => {
    expect(
      dictionaryTypeSelectOptions([
        { dictName: 'User gender', dictType: 'SYS_USER_SEX' },
      ]),
    ).toEqual([
      {
        label: 'User gender (SYS_USER_SEX)',
        value: 'SYS_USER_SEX',
      },
    ]);
  });
});
