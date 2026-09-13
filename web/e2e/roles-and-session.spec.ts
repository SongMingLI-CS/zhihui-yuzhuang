import { test, expect, type Page } from '@playwright/test';

/**
 * 角色分区 / 会话守卫 / 商家工作台 E2E（阶段 H）。
 *
 * <p>全程使用路由替身（route mock），不依赖后端与数据库，因此在 CI 与本地均可跑。
 * 验证的是**前端行为**（跳转、菜单分区、按钮可见性）；服务端授权由 backend 的
 * 端点策略与安全矩阵单测保证（见 SecurityMatrixTest）。
 */
const ok = <T,>(data: T) => ({ code: '00000', message: 'success', data, timestamp: Date.now(), requestId: 'e2e' });

type Role = 'COOPERATIVE' | 'VILLAGE' | 'GOVERNMENT' | 'PLATFORM_ADMIN';

const userOf = (role: Role) => ({
  userId: 1,
  username: 'e2e-user',
  displayName: `E2E ${role}`,
  tenantId: role === 'PLATFORM_ADMIN' ? 'tenant_platform_000' : 'tenant_yuzhuang_001',
  role,
});

async function mockCommon(page: Page, role: Role | null) {
  // 事件流：SSE 以空体结束，避免长连接影响用例
  await page.route('**/api/v1/events/stream*', (route) => route.fulfill({ status: 200, contentType: 'text/event-stream', body: ':ok\n\n' }));
  await page.route('**/api/v1/events/recent*', (route) => route.fulfill({ json: ok([]) }));
  await page.route('**/healthz*', (route) => route.fulfill({ json: ok({ status: 'UP' }) }));
  await page.route('**/api/v1/dashboard/summary*', (route) => route.fulfill({
    json: ok({
      tenantId: 'tenant_yuzhuang_001', snapshotAt: Date.now(), totalOrders: 3, totalSales: 0,
      todayOrders: 0, todaySales: 0, pendingPayOrders: 0, readyShipOrders: 0,
      trend: [], topProducts: [],
    }),
  }));
  if (role) {
    await page.route('**/api/v1/auth/me*', (route) => route.fulfill({ json: ok(userOf(role)) }));
  } else {
    await page.route('**/api/v1/auth/me*', (route) => route.fulfill({ status: 401, json: { code: 'A1002', message: '未登录或登录已过期', data: null, timestamp: Date.now(), requestId: 'e2e' } }));
  }
}

test('未登录访问 /b/dashboard 会被守卫重定向到 /b/login', async ({ page }) => {
  await mockCommon(page, null);
  await page.goto('/b/dashboard');
  await expect(page).toHaveURL(/\/b\/login$/);
  await expect(page.getByRole('button', { name: '登录' })).toBeVisible();
});

test('登录失败（A1002）时显示错误且停留在登录页', async ({ page }) => {
  await mockCommon(page, null);
  await page.route('**/api/v1/auth/login', (route) => route.fulfill({
    status: 401,
    json: { code: 'A1002', message: '用户名或密码错误', data: null, timestamp: Date.now(), requestId: 'e2e' },
  }));
  await page.goto('/b/login');
  await page.getByLabel('用户名').fill('admin');
  await page.getByLabel('密码').fill('wrong-pass');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page.getByRole('alert')).toContainText('用户名或密码错误');
  await expect(page).toHaveURL(/\/b\/login$/);
});

test('合作社登录后进入商家工作台，且不显示治理大盘入口', async ({ page }) => {
  await mockCommon(page, 'COOPERATIVE');
  await page.route('**/api/v1/merchant/products**', (route) => route.fulfill({
    json: ok({
      items: [
        { id: 1, skuCode: 'SKU-ON', spuName: '在售商品', price: 10, stock: 5, status: 'ON_SALE', tenantId: 'tenant_yuzhuang_001' },
        { id: 2, skuCode: 'SKU-OFF', spuName: '已下架商品', price: 12, stock: 0, status: 'OFF_SHELF', tenantId: 'tenant_yuzhuang_001' },
      ],
      page: 1, pageSize: 10, total: 2, totalPages: 1,
    }),
  }));
  await page.route('**/api/v1/auth/login', (route) => route.fulfill({
    json: ok({ token: 'e2e-token', tokenType: 'Bearer', expiresIn: 7200, mustChangePassword: false, user: userOf('COOPERATIVE') }),
  }));

  await page.goto('/b/login');
  await page.getByLabel('用户名').fill('coop001');
  await page.getByLabel('密码').fill('coop123');
  await page.getByRole('button', { name: '登录' }).click();

  await expect(page).toHaveURL(/\/b\/merchant$/);
  // 下架商品仍可见（修复“下架即消失”）
  await expect(page.getByText('已下架商品')).toBeVisible();
  await expect(page.getByText('已下架', { exact: false })).toBeVisible();
  // 合作社菜单不包含治理大盘
  await expect(page.getByRole('link', { name: /产业治理大盘/ })).toHaveCount(0);
});

test('政府登录后进入 /b/gov 只读大屏（无商品/订单写操作入口）', async ({ page }) => {
  await mockCommon(page, 'GOVERNMENT');
  await page.route('**/api/v1/gov/summary**', (route) => route.fulfill({
    json: ok({
      snapshotAt: Date.now(),
      scope: { all: false, tenantCount: 1, description: '政府：授权 1 个租户' },
      metrics: { salesAmount: '交易额 = 非 CANCELLED 订单合计', orderCount: '全部订单数', cancelledOrders: '取消单不计交易额', version: '2026.09-v1' },
      totals: { totalOrders: 3, totalSales: 0, todayOrders: 0, todaySales: 0, pendingPayOrders: 0, readyShipOrders: 0 },
      breakdown: [{ tenantId: 'tenant_yuzhuang_001', tenantName: '鹿邑试量镇于庄村股份经济合作社', region: '河南省周口市鹿邑县试量镇', totalOrders: 3, totalSales: 0, todayOrders: 0 }],
    }),
  }));
  await page.route('**/api/v1/auth/login', (route) => route.fulfill({
    json: ok({ token: 'e2e-token', tokenType: 'Bearer', expiresIn: 7200, mustChangePassword: false, user: userOf('GOVERNMENT') }),
  }));

  await page.goto('/b/login');
  await page.getByLabel('用户名').fill('gov001');
  await page.getByLabel('密码').fill('gov12345');
  await page.getByRole('button', { name: '登录' }).click();

  await expect(page).toHaveURL(/\/b\/gov$/);
  await expect(page.getByText('区域产业治理（只读）')).toBeVisible();
  await expect(page.getByText(/数据更新时间/)).toBeVisible();
  await expect(page.getByText(/统计口径/)).toBeVisible();
  // 只读：页面上不得出现写操作按钮
  await expect(page.getByRole('button', { name: /上架|下架|编辑|删除|发货|出库/ })).toHaveCount(0);
  await expect(page.getByRole('link', { name: /商品管理|订单与出库流水/ })).toHaveCount(0);
});

test('过期会话：/auth/me 返回 401 时回到登录页', async ({ page }) => {
  await mockCommon(page, null);
  await page.goto('/b/products');
  await expect(page).toHaveURL(/\/b\/login$/);
});
