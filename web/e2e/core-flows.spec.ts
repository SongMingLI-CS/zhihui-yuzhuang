import { test, expect, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

const ok = <T,>(data: T) => ({ code: '00000', message: 'success', data, timestamp: Date.now(), requestId: 'e2e-request' });
const browserErrors = new WeakMap<Page, string[]>();

test.beforeEach(async ({ page }) => {
  const errors: string[] = []; browserErrors.set(page, errors);
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
  await page.route('**/healthz', (route) => route.fulfill({ json: ok({ status: 'UP' }) }));
});

test.afterEach(async ({ page }) => expect(browserErrors.get(page) ?? [], '浏览器控制台不应出现错误').toEqual([]));

test('B 端：搜索知识库 → RAG 验证 → 生成报告 → 审批', async ({ page }) => {
  await page.route('**/ai/v1/qa/ask', (route) => route.fulfill({ json: ok({ answer: '返青期应先查墒情，再按苗情追肥。', citations: [{ docTitle: '小麦种植指南', pageNumber: 8, chunkText: '返青期管理建议', similarityScore: 0.91 }] }) }));
  await page.route('**/ai/v1/marketing/generate', (route) => route.fulfill({ json: ok({ thought_chain: [{ agent_name: 'TrendAgent', output_summary: '需求分析完成' }, { agent_name: 'CopywriterAgent', output_summary: '文案完成' }, { agent_name: 'ComplianceAgent', output_summary: '合规通过' }], copies: [{ channel: 'MOMENTS', title: '于庄好物', content: '合作社直发的小磨香油。', call_to_action: '立即了解' }], compliance: { score: 96, passed: true, risk_terms_detected: [], revision_suggestions: [] }, review_status: 'PENDING_REVIEW' }) }));
  await page.goto('/b/knowledge');
  await page.getByLabel('搜索知识文献').fill('小麦');
  await expect(page.getByText('于庄小麦种植指南')).toHaveCount(2);
  await page.locator('button:visible').filter({ hasText: '检索验证' }).first().click();
  await page.getByRole('textbox').last().fill('返青期如何追肥？');
  await page.getByRole('dialog').getByRole('button', { name: '检索' }).click();
  await expect(page.getByText(/返青期应先查墒情/)).toBeVisible();
  await page.getByRole('button', { name: '关闭' }).click();
  await page.goto('/b/agents');
  await page.getByRole('button', { name: /小磨香油/ }).click();
  await page.getByRole('button', { name: /开始生成/ }).click();
  await expect(page.getByRole('button', { name: /审批通过并同步/ })).toBeVisible();
  await page.getByRole('button', { name: /审批通过并同步/ }).click();
  await expect(page.getByText(/审批通过，已同步/)).toBeVisible();
});

test('H5：浏览商品 → 表单校验 → 成功下单', async ({ page }) => {
  const product = { id: 1, skuCode: 'OIL-01', spuName: '于庄小磨香油', price: 39.9, stock: 12, status: 'ON_SALE', tenantId: 'tenant_yuzhuang_001', description: '古法石磨', imageUrl: '' };
  await page.route('**/api/v1/products', (route) => route.fulfill({ json: ok([product]) }));
  await page.route('**/api/v1/orders/checkout', (route) => route.fulfill({ json: ok({ orderNo: 'ORD-E2E-001', totalAmount: 39.9, status: 'PENDING_PAY', expireTime: null }) }));
  await page.goto('http://127.0.0.1:5173/h5/');
  await expect(page.getByText('于庄小磨香油')).toBeVisible();
  await page.getByRole('button', { name: /立即抢购/ }).click();
  await page.getByRole('button', { name: /立即抢购 ¥/ }).click();
  await expect(page.getByText('请填写收货人姓名')).toBeVisible();
  await page.getByRole('button', { name: /一键填入演示/ }).click();
  await page.getByRole('button', { name: /立即抢购 ¥/ }).click();
  await expect(page.getByText('ORD-E2E-001')).toBeVisible();
});

test('关键页面无严重或致命 a11y 问题', async ({ page }) => {
  await page.route('**/api/v1/products', (route) => route.fulfill({ json: ok([]) }));
  for (const url of ['/b/dashboard', '/b/knowledge', '/b/agents', '/b/orders']) {
    await page.goto(url);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth), `${url} 不应横向溢出`).toBe(true);
    const results = await new AxeBuilder({ page }).analyze();
    expect(results.violations.filter((item) => ['serious', 'critical'].includes(item.impact ?? ''))).toEqual([]);
  }
  await page.goto('/b/knowledge');
  await page.locator('button:visible').filter({ hasText: '农技检索验证' }).click();
  await page.waitForTimeout(400);
  let results = await new AxeBuilder({ page }).include('[role="dialog"]').analyze();
  expect(results.violations.filter((item) => ['serious', 'critical'].includes(item.impact ?? ''))).toEqual([]);
  await page.getByRole('button', { name: '关闭' }).click();
  await page.goto('/b/dashboard');
  await page.getByRole('button', { name: '清屏' }).click();
  await page.waitForTimeout(400);
  results = await new AxeBuilder({ page }).include('[role="alertdialog"]').analyze();
  expect(results.violations.filter((item) => ['serious', 'critical'].includes(item.impact ?? ''))).toEqual([]);
  await page.goto('http://127.0.0.1:5173/h5/');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth), 'H5 不应横向溢出').toBe(true);
  results = await new AxeBuilder({ page }).analyze();
  expect(results.violations.filter((item) => ['serious', 'critical'].includes(item.impact ?? ''))).toEqual([]);
});
