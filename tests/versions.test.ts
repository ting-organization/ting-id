// Whether a release on GitHub is newer than the one this phone runs.
import { describe, expect, it } from 'vitest';
import { newer, versionOf } from '../src/ui/versions';

describe('versions', () => {
  it('read as three numbers, with or without a v', () => {
    expect(versionOf('v0.2.4')).toEqual([0, 2, 4]);
    expect(versionOf('1.10.0')).toEqual([1, 10, 0]);
    expect(versionOf('v1.2')).toBeNull();
  });
  it('compare by number, not as text', () => {
    expect(newer('v0.10.0', '0.9.9')).toBe(true);
    expect(newer('v0.2.4', '0.2.4')).toBe(false);
    expect(newer('v0.1.0', '0.2.4')).toBe(false);
    expect(newer('nightly', '0.2.4')).toBe(false);
  });
});
