-- ============================================================================
-- 智汇于庄 · 属地化业务种子数据（鹿邑县试量镇于庄村）
-- ----------------------------------------------------------------------------
-- 目标环境：rural-revitalization-postgres / 库 rural_revitalization
-- 运行方式（经 Makefile）：
--   make seed
-- 等价于：
--   docker compose -f deploy/docker-compose.yml exec -T postgres \
--     psql -U rural_user -d rural_revitalization -v ON_ERROR_STOP=1 \
--     -f /dev/stdin < deploy/seed-realistic-data.sql
--
-- 写入内容：
--   1) 3 款属地化在售商品（SKU1001/1002/1003，归属 global 共享目录 —— 租户
--      tenant_yuzhuang_001 通过「tenant_id = 本租户 OR 'global'」可见，因此商品
--      读接口恰好返回 3 款，避免跨租户重复扩容导致 6 款）；
--   2) 最近 7 天 50 条真实感订单（收货地覆盖 鹿邑县城 / 试量镇各村 / 郑州 / 开封，
--      时间落在 19:00~22:00 直播带货波峰，呈 ECharts 波浪曲线），状态覆盖
--      PENDING_PAY / STOCK_CONFIRMED / PROCESSING / PAID / SHIPPED / COMPLETED / CANCELLED；
--   3) 与 50 条订单一一对应的 Outbox 事件（PROCESSED 为主 + 3 条 PUBLISHED 模拟刚投递）。
--
-- 幂等设计（可安全重复执行）：
--   - 以 idempotency_key LIKE 'seed-order-%' 为标记：先删旧种子订单/明细/Outbox 再重建；
--   - 商品 SKU 以 (tenant_id, sku_code) 唯一键 UPSERT：清理旧占位商品后 upsert 规范 SKU，
--     已存在时仅校准 id/名称/价格/状态，不动库存（保留运行期 CAS 扣减结果）。
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 0) 幂等清理：删除此前写入的种子订单 / 明细 / Outbox（以标记前缀识别）
-- ----------------------------------------------------------------------------
DELETE FROM t_outbox_event
WHERE aggregate_id IN (
    SELECT order_no FROM t_order WHERE idempotency_key LIKE 'seed-order-%'
);

DELETE FROM t_order_item
WHERE order_no IN (
    SELECT order_no FROM t_order WHERE idempotency_key LIKE 'seed-order-%'
);

DELETE FROM t_order
WHERE idempotency_key LIKE 'seed-order-%';

-- ----------------------------------------------------------------------------
-- 1) 属地化在售商品 SKU（显式主键 1001/1002/1003，保证验证脚本可确定性下单）
--    - 清理早期占位商品（SKU-SESAME-OIL-001 等），避免历史镜像自举时残留；
--    - 规范 SKU 归属 global（共享目录），租户查询可见且总数恰为 3；
--    - UPSERT 只校准 主键/名称/价格/状态，不改 stock，保留运行期扣减结果。
-- ----------------------------------------------------------------------------
-- 仅清理历史占位商品；规范 SKU1001-3 交给下方 UPSERT 校准（不动 stock，
-- 保证重复 make seed 不会把运行期 CAS 扣减的真实库存重置回初始值）。
DELETE FROM t_product_sku
WHERE tenant_id = 'global'
  AND sku_code IN (
        'SKU-SESAME-OIL-001',   -- 旧占位：于庄传统石磨小磨香油
        'SKU-WHEAT-FLOUR-001',  -- 旧占位：于庄富硒石磨小麦粉
        'SKU-WILD-HONEY-001'    -- 旧占位：于庄荆条土蜂蜜
      );

INSERT INTO t_product_sku
    (id, tenant_id, sku_code, spu_name, price, stock, version, status)
VALUES
    (1001, 'global', 'SKU1001', '鹿邑试量传统石磨小磨香油（500ml）',   68.00,  800, 0, 'ON_SALE'),
    (1002, 'global', 'SKU1002', '于庄富硒石磨全胚芽小麦粉（5kg）',    45.00, 1500, 0, 'ON_SALE'),
    (1003, 'global', 'SKU1003', '豫东传统纯手工红薯粉条（1kg）',      26.80, 1200, 0, 'ON_SALE')
ON CONFLICT (tenant_id, sku_code)
DO UPDATE SET id       = EXCLUDED.id,
              spu_name = EXCLUDED.spu_name,
              price    = EXCLUDED.price,
              status   = EXCLUDED.status;

-- 校准商品表自增序列：确保此后应用新增 SKU 主键不与显式 1001~1003 冲突
SELECT setval(
    pg_get_serial_sequence('t_product_sku', 'id'),
    GREATEST((SELECT COALESCE(MAX(id), 1) FROM t_product_sku), 2000)
);

-- ----------------------------------------------------------------------------
-- 2) 生成 50 条订单（订单行 + 明细 + 小计全部由 SQL 计算，天然自洽）
--
-- 分布设计（最近 7 天波浪曲线，按天订单量 3/8/7/9/6/9/8）：
--   day_off = 0..6 表示「距今天数」，数组下标即订单序号 n（1..50）；
--   单日内以 19:00~22:00 直播带货高峰为主，穿插少量 10 点/14 点白天下单。
--
-- 收货人 / 电话 / 地址 / 备注由真实感数组轮转取用；明细（最多 3 行、覆盖
-- 多商品组合礼包）由确定性取模规则生成，金额由单价×数量实时计算。
-- ----------------------------------------------------------------------------

WITH base AS (
    SELECT
        n,
        'tenant_yuzhuang_001'::VARCHAR                AS tenant_id,
        -- 近 7 天分布：day_off 数组（下标 1..50）
        (ARRAY[
            0,0,0,
            1,1,1,1,1,1,1,1,
            2,2,2,2,2,2,2,
            3,3,3,3,3,3,3,3,3,
            4,4,4,4,4,4,
            5,5,5,5,5,5,5,5,5,
            6,6,6,6,6,6,6,6
        ])[n]                                         AS day_off,
        -- 单日时刻：夜间直播波峰 + 少量白天下单
        make_time(
            CASE
                WHEN n % 11 = 0 THEN 10 + ((n * 3) % 2)   -- 10 点档
                WHEN n % 7  = 0 THEN 14 + ((n * 2) % 3)   -- 14~16 点档
                ELSE 19 + ((n * 3) % 4)                   -- 19~22 直播波峰
            END,
            (n * 17) % 60,
            (n * 11) % 60
        )                                               AS order_time,
        -- 渠道：抖音/快手直播为主，穿插 H5 私域与 B2B 集采
        (ARRAY['DOUYIN','KUAISHOU','H5_PRIVATE','DOUYIN','B2B_PORTAL','H5_PRIVATE'])[((n - 1) % 6) + 1] AS order_source,
        -- 收货人 / 电话 / 地址 / 备注（轮转取用，保证覆盖鹿邑县城·试量镇各村·郑州·开封）
        (ARRAY['张春生','李秀兰','王建军','刘桂香','赵德柱','陈红梅',
               '杨玉兰','马国涛','孙俊霞','郭志强','崔永峰','于海霞'])[((n - 1) % 12) + 1] AS recipient_name,
        (ARRAY['13837281234','13938561278','13733886677','15890551236','18738551230',
               '15138567823','13633990012','15938567712','13273112233','18837123456'])[((n - 1) % 10) + 1] AS recipient_phone,
        (ARRAY[
            '河南省周口市鹿邑县试量镇于庄村村委会东50米',
            '河南省周口市鹿邑县试量镇于庄村三组',
            '河南省周口市鹿邑县试量镇试量集贸市场北口',
            '河南省周口市鹿邑县试量镇北街农资门市部',
            '河南省周口市鹿邑县真源大道辅仁花园3号楼2单元',
            '河南省周口市鹿邑县紫气大道中段明道花园8号楼',
            '河南省周口市鹿邑县卫真路新城国际2期5号楼',
            '河南省周口市鹿邑县仙台路地税局家属院3号楼',
            '河南省周口市鹿邑县鸣鹿办事处金日家园6号楼',
            '河南省周口市鹿邑县试量镇刘楼村卫生室对面',
            '河南省郑州市金水区丰庆路街道瀚宇天悦城2期',
            '河南省郑州市二七区大学路街道中原东路87号',
            '河南省郑州市中原区棉纺路街道锦艺城B区',
            '河南省开封市龙亭区午朝门街道迪臣世博广场',
            '河南省开封市鼓楼区西司门街道省府西街',
            '河南省周口市鹿邑县试量镇崔大庄村北'
        ])[((n - 1) % 16) + 1] AS detailed_address,
        (ARRAY[
            '抖音直播间「于庄土特产小丽」下单',
            '快手直播间秒杀，香油+粉条组合',
            '微信朋友圈团购，老客复购',
            '好友推荐，要求真空包装发顺丰',
            'B2B 郑州商超集采，发物流专线',
            '小红书种草，礼盒装走亲访友',
            '村口广播团购，到于庄村自提点取货',
            '返乡游子给家中长辈寄的年货'
        ])[((n - 1) % 8) + 1]                            AS remark,
        -- 状态覆盖：旧单 COMPLETED/CANCELLED → SHIPPED → PAID → 新单
        CASE
            WHEN (ARRAY[
                0,0,0,
                1,1,1,1,1,1,1,1,
                2,2,2,2,2,2,2,
                3,3,3,3,3,3,3,3,3,
                4,4,4,4,4,4,
                5,5,5,5,5,5,5,5,5,
                6,6,6,6,6,6,6,6
            ])[n] >= 5 THEN CASE WHEN n % 7 = 0 THEN 'CANCELLED' ELSE 'COMPLETED' END
            WHEN (ARRAY[
                0,0,0,
                1,1,1,1,1,1,1,1,
                2,2,2,2,2,2,2,
                3,3,3,3,3,3,3,3,3,
                4,4,4,4,4,4,
                5,5,5,5,5,5,5,5,5,
                6,6,6,6,6,6,6,6
            ])[n] = 4 THEN 'SHIPPED'
            WHEN (ARRAY[
                0,0,0,
                1,1,1,1,1,1,1,1,
                2,2,2,2,2,2,2,
                3,3,3,3,3,3,3,3,3,
                4,4,4,4,4,4,
                5,5,5,5,5,5,5,5,5,
                6,6,6,6,6,6,6,6
            ])[n] = 3 THEN CASE WHEN n % 6 = 0 THEN 'CANCELLED' ELSE 'SHIPPED' END
            WHEN (ARRAY[
                0,0,0,
                1,1,1,1,1,1,1,1,
                2,2,2,2,2,2,2,
                3,3,3,3,3,3,3,3,3,
                4,4,4,4,4,4,
                5,5,5,5,5,5,5,5,5,
                6,6,6,6,6,6,6,6
            ])[n] = 2 THEN CASE WHEN n % 5 = 0 THEN 'PROCESSING' ELSE 'PAID' END
            WHEN (ARRAY[
                0,0,0,
                1,1,1,1,1,1,1,1,
                2,2,2,2,2,2,2,
                3,3,3,3,3,3,3,3,3,
                4,4,4,4,4,4,
                5,5,5,5,5,5,5,5,5,
                6,6,6,6,6,6,6,6
            ])[n] = 1 THEN CASE WHEN n % 4 = 0 THEN 'STOCK_CONFIRMED' ELSE 'PAID' END
            ELSE CASE WHEN n % 2 = 0 THEN 'PENDING_PAY' ELSE 'STOCK_CONFIRMED' END
        END                                             AS status,
        -- 明细组合（确定性取模：1~3 行，行 2/行 3 覆盖多商品礼包）
        ((n - 1) % 3) + 1                               AS idx1,
        ((n * 7) % 3) + 1                               AS qty1,
        n % 2 = 0                                       AS has_l2,
        (((n - 1) % 3) + 1) % 3 + 1                     AS idx2,
        ((n * 5) % 3) + 1                               AS qty2,
        n % 3 = 0                                       AS has_l3,
        (((((n - 1) % 3) + 1) % 3 + 1) % 3) + 1         AS idx3,
        ((n * 11) % 3) + 1                              AS qty3
    FROM generate_series(1, 50) AS s(n)
),
-- 展开每条订单的明细行（至多 3 行）
item_lines AS (
    SELECT b.n,
           x.l,
           (ARRAY['SKU1001','SKU1002','SKU1003'])[x.idx] AS sku_code,
           x.qty
    FROM base b
    CROSS JOIN LATERAL (
        VALUES
            (1, b.idx1, b.qty1),
            (2, b.idx2, b.qty2),
            (3, b.idx3, b.qty3)
    ) AS x(l, idx, qty)
    WHERE (x.l = 1)
       OR (x.l = 2 AND b.has_l2)
       OR (x.l = 3 AND b.has_l3)
),
-- 先写主订单（总额=明细实时求和），再写明细（同一语句内数据修改 CTE）
ins_orders AS (
    INSERT INTO t_order (
        tenant_id, order_no, idempotency_key, order_source,
        total_amount, status, recipient_name, recipient_phone,
        detailed_address, remark, created_at
    )
    SELECT
        b.tenant_id,
        'YZ' || to_char((CURRENT_DATE - b.day_off) + b.order_time, 'YYYYMMDD')
            || lpad(b.n::TEXT, 2, '0')                            AS order_no,
        'seed-order-' || lpad(b.n::TEXT, 2, '0')                  AS idempotency_key,
        b.order_source,
        (SELECT round(SUM(il.qty * p.price)::NUMERIC, 2)
           FROM item_lines il
           JOIN t_product_sku p
             ON p.sku_code = il.sku_code AND p.tenant_id = 'global'
          WHERE il.n = b.n)                                      AS total_amount,
        b.status,
        b.recipient_name,
        b.recipient_phone,
        b.detailed_address,
        b.remark,
        (CURRENT_DATE - b.day_off) + b.order_time                AS created_at
    FROM base b
    RETURNING order_no
)
INSERT INTO t_order_item (order_no, sku_id, quantity, unit_price, subtotal)
SELECT
    io.order_no,
    p.id,
    il.qty,
    p.price,
    round((il.qty * p.price)::NUMERIC, 2)
FROM ins_orders io
-- order_no 尾部 2 位即订单序号 n（lpad(n,2,'0')），据此把明细精确挂到已落库的种子订单
JOIN item_lines il
  ON il.n = substring(io.order_no FROM '([0-9]{2})$')::INT
JOIN t_product_sku p
  ON p.sku_code = il.sku_code AND p.tenant_id = 'global';

-- 校准订单相关自增序列（防显式/自动主键混淆，仅美化非必需）
SELECT setval(pg_get_serial_sequence('t_order', 'id'),
              GREATEST((SELECT COALESCE(MAX(id), 1) FROM t_order), 1));

-- ----------------------------------------------------------------------------
-- 3) 为 50 条种子订单生成一一对应的 Outbox 事件
--    - aggregate_type=ORDER / event_type=ORDER_CREATED / aggregate_id=order_no；
--    - payload 字段契约与 OrderServiceImpl.buildOutboxEvent 完全一致：
--      eventType/orderNo/tenantId/orderSource/totalAmount/status/items/createdAt；
--      （事件内的 status 取落单时点快照 STOCK_CONFIRMED —— 与服务端新建一致）
--    - 投递状态：最近 3 单 PUBLISHED（模拟刚 XADD 到 Redis Stream 未确认），
--      其余 PROCESSED（已消费确认）；不写 PENDING，避免 OutboxSweeper 重复投递。
-- ----------------------------------------------------------------------------
INSERT INTO t_outbox_event (
    tenant_id, aggregate_type, aggregate_id, event_type,
    payload, status, retry_count, created_at
)
SELECT
    o.tenant_id,
    'ORDER',
    o.order_no,
    'ORDER_CREATED',
    jsonb_build_object(
        'eventType',   'ORDER_CREATED',
        'orderNo',     o.order_no,
        'tenantId',    o.tenant_id,
        'orderSource', o.order_source,
        'totalAmount', o.total_amount,
        'status',      'STOCK_CONFIRMED',
        'items', (SELECT COALESCE(jsonb_agg(
                                jsonb_build_object(
                                    'skuId',    i.sku_id,
                                    'quantity', i.quantity,
                                    'unitPrice', i.unit_price,
                                    'subtotal', i.subtotal
                                )
                            ), '[]'::JSONB)
                   FROM t_order_item i WHERE i.order_no = o.order_no),
        'createdAt',   to_char(o.created_at, 'YYYY-MM-DD"T"HH24:MI:SS')
    )::TEXT,
    CASE WHEN o.idempotency_key IN (
            'seed-order-01', 'seed-order-02', 'seed-order-03'
         )
         THEN 'PUBLISHED' ELSE 'PROCESSED' END,
    0,
    o.created_at
FROM t_order o
WHERE o.idempotency_key LIKE 'seed-order-%';

-- ----------------------------------------------------------------------------
-- 4) 汇总自检（make seed 应输出：on_sale_skus=3, seeded_orders=50,
--      seeded_items=~85, seeded_outbox=50, published_outbox=3）
-- ----------------------------------------------------------------------------
SELECT
    (SELECT count(*) FROM t_product_sku WHERE tenant_id = 'global' AND status = 'ON_SALE')
        AS on_sale_skus,
    (SELECT count(*) FROM t_order WHERE idempotency_key LIKE 'seed-order-%')
        AS seeded_orders,
    (SELECT count(*) FROM t_order_item it
       JOIN t_order o ON o.order_no = it.order_no
      WHERE o.idempotency_key LIKE 'seed-order-%')
        AS seeded_items,
    (SELECT count(*) FROM t_outbox_event WHERE aggregate_id LIKE 'YZ%')
        AS seeded_outbox,
    (SELECT count(*) FROM t_outbox_event
      WHERE aggregate_id LIKE 'YZ%' AND status = 'PUBLISHED')
        AS published_outbox;

COMMIT;
