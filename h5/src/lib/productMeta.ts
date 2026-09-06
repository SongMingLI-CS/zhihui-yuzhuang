import type { Product } from '../types/api';
import { formatStock } from './format';

export type TagTone = 'green' | 'amber' | 'red' | 'blue';

export interface ProductTag {
  text: string;
  tone: TagTone;
}

const TONE_CLASSES: Record<TagTone, string> = {
  green: 'bg-green-100 text-green-700',
  amber: 'bg-amber-100 text-amber-700',
  red: 'bg-red-100 text-red-600',
  blue: 'bg-sky-100 text-sky-700',
};

export function tagClass(tone: TagTone): string {
  return TONE_CLASSES[tone];
}

/**
 * 特产标签：后端 ProductSkuResponse 未含 tags 字段，故按商品名/SKU 编码
 * 派生展示标签（非遗石磨 / 绿色农产品 / 于庄直发等）。
 */
export function productTags(p: Product): ProductTag[] {
  const name = p.spuName || '';
  const code = p.skuCode || '';
  if (name.includes('香油') || code.includes('SESAME')) {
    return [
      { text: '非遗石磨', tone: 'amber' },
      { text: '于庄直发', tone: 'green' },
    ];
  }
  if (name.includes('小麦粉') || name.includes('面粉') || code.includes('FLOUR')) {
    return [
      { text: '绿色农产品', tone: 'green' },
      { text: '富硒认证', tone: 'blue' },
      { text: '于庄直发', tone: 'green' },
    ];
  }
  if (name.includes('蜂蜜') || name.includes('蜂') || code.includes('HONEY')) {
    return [
      { text: '天然土蜂蜜', tone: 'amber' },
      { text: '于庄直发', tone: 'green' },
    ];
  }
  return [{ text: '于庄直发', tone: 'green' }];
}

export interface StockMeta {
  text: string;
  soldOut: boolean;
  low: boolean;
}

/** 剩余库存实时徽章：售罄/紧张/常规三态 */
export function stockMeta(p: Product): StockMeta {
  if (p.stock <= 0) {
    return { text: '已抢光', soldOut: true, low: false };
  }
  if (p.stock <= 30) {
    return { text: `仅剩 ${formatStock(p.stock)} 件`, soldOut: false, low: true };
  }
  return { text: `仅剩 ${formatStock(p.stock)} 件`, soldOut: false, low: false };
}
