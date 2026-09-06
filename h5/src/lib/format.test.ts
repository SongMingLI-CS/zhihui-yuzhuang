import { describe, expect, it } from 'vitest';
import { formatClock, formatPrice, toClock } from './format';
import { productTags, stockMeta } from './productMeta';
import type { Product } from '../types/api';

const product = (overrides: Partial<Product> = {}): Product => ({
  id: 1,
  skuCode: 'SKU1001',
  spuName: '鹿邑试量传统石磨小磨香油（500ml）',
  price: 68,
  stock: 799,
  status: 'ON_SALE',
  tenantId: 'tenant_yuzhuang_001',
  description: '产地直供',
  imageUrl: '',
  ...overrides,
});

describe('storefront presentation helpers', () => {
  it('formats prices and countdowns', () => {
    expect(formatPrice(68)).toBe('¥68.00');
    expect(formatClock(toClock(3_661_000))).toBe('01:01:01');
  });

  it('derives product tags and stock states', () => {
    expect(productTags(product()).map((item) => item.text)).toContain('非遗石磨');
    expect(stockMeta(product({ stock: 9 })).low).toBe(true);
    expect(stockMeta(product({ stock: 0 })).soldOut).toBe(true);
  });
});
