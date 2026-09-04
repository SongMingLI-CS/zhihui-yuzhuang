-- ============================================================
-- 智汇于庄 backend · 商品 SKU 种子数据（容器环境自举用）
--
-- 触发方式：由 docker-compose 通过环境变量注入开启 SQL 初始化
--   SPRING_SQL_INIT_MODE=always
--   SPRING_SQL_INIT_SCHEMA_LOCATIONS=classpath:db/schema.sql
--   SPRING_SQL_INIT_DATA_LOCATIONS=classpath:db/seed-data.sql
-- （仅容器启动时执行；不影响本地 H2 测试上下文。）
--
-- 属地化三款在售商品（SKU1001/1002/1003）：
--   与 deploy/seed-realistic-data.sql 保持一致，显式主键 1001/1002/1003，
--   归属 tenant 'global'（共享目录）。租户 tenant_yuzhuang_001 经读接口的
--   「tenant_id = 本租户 OR 'global'」可见，因此商品列表恰好返回 3 款。
--
-- 幂等设计：
--   1. 清理早期占位商品（SKU-SESAME-OIL-001 等，仅当历史镜像残留时命中）；
--   2. 依赖 (tenant_id, sku_code) 唯一约束 UPSERT：已存在时只校准
--      主键/名称/价格/状态，不改 stock —— 保留运行期 CAS 下单扣减的真实库存，
--      重复启动不会重复插入/报错。
-- ============================================================

-- 状态说明：status 采用读接口契约值 'ON_SALE'（在售），与
-- ProductSkuMapper.selectAvailableList 的 WHERE status = 'ON_SALE' 过滤一致。
-- 仅清理历史占位商品；规范 SKU1001-3 交给下方 UPSERT 校准（不动 stock，
-- 避免容器重启把运行期 CAS 扣减的真实库存重置回初始值）。
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

-- 校准商品表自增序列：确保后续应用新增 SKU 主键不与显式 1001~1003 冲突
SELECT setval(
    pg_get_serial_sequence('t_product_sku', 'id'),
    GREATEST((SELECT COALESCE(MAX(id), 1) FROM t_product_sku), 2000)
);
