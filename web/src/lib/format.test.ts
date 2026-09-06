import { describe, expect, it } from 'vitest';
import { formatCNY, formatDate, formatTime, formatYuan, round2 } from './format';

describe('web formatting helpers', () => {
  it('formats governance metrics consistently', () => {
    expect(formatYuan(12_864_300)).toBe('¥1286.4万');
    expect(formatCNY(11074)).toBe('¥11,074.00');
    expect(round2(1.235)).toBe(1.24);
  });

  it('formats local date and time with stable padding', () => {
    const date = new Date(2026, 8, 6, 7, 8, 9);
    expect(formatDate(date)).toBe('2026-09-06');
    expect(formatTime(date)).toBe('07:08:09');
  });
});
