/**
 * 广告法合规词库前端镜像（分类 / 输入源二次高亮用）。
 *
 * 权威检测在 ai-service 的 ComplianceAgent（确定性规则引擎，见
 * ai-service/app/agents/marketing_agents.py）：返回的
 * `ComplianceReport.risk_terms_detected` 才是判定依据。
 * 此处词表仅用于两件事：
 *   1) 把 API 命中的敏感词分组展示为「绝对化用语（广告法第九条）」
 *      /「疾病预防/治疗宣称（广告法第十七条）」两类；
 *   2) 在产品名 / 卖点等输入源上做二次高亮定位，便于运营负责人复核整改。
 *
 * 词表为 ai-service 词表快照镜像，保持与契约一致；新增词应同步回 ai-service。
 */

export type RiskKind = 'absolute' | 'medical';

/** 绝对化用语（《广告法》第九条）——前端镜像 */
export const ABSOLUTE_TERMS: string[] = [
  '国家级',
  '世界级',
  '全球第一',
  '世界第一',
  '全国第一',
  '全网第一',
  '销量第一',
  '天下第一',
  '顶级',
  '顶尖',
  '最高级',
  '最高端',
  '最佳',
  '最好',
  '最强',
  '最优',
  '极致',
  '之王',
  '王牌',
  '首选',
  '唯一',
  '首个',
  '首创',
  '绝无仅有',
  '独一无二',
  '第一',
  'NO.1',
  'No.1',
  'no.1',
];

/** 疾病预防/治疗宣称（《广告法》第十七条，普通食品禁用）——前端镜像 */
export const MEDICAL_TERMS: string[] = [
  '包治百病',
  '治胃病',
  '治百病',
  '治病',
  '治疗',
  '疗效',
  '药到病除',
  '根治',
  '治愈',
  '痊愈',
  '防癌',
  '抗癌',
  '抗肿瘤',
  '降血压',
  '降血糖',
  '降血脂',
  '降压',
  '降糖',
  '降脂',
  '预防疾病',
  '预防感冒',
  '防感冒',
  '消炎',
  '杀菌',
  '止痛',
  '消肿',
  '祛湿',
  '排毒',
  '壮阳',
  '补肾',
  '延年益寿',
  '调理脾胃',
  '养胃治胃',
  '百病',
];

export const RISK_KIND_LABEL: Record<RiskKind, string> = {
  absolute: '绝对化用语（广告法第九条）',
  medical: '疾病预防/治疗宣称（广告法第十七条）',
};

/** 把 API 返回的敏感词归类：medical（疾病宣称） / absolute（绝对化） */
export function classifyTerm(term: string): RiskKind {
  if (MEDICAL_TERMS.includes(term)) {
    return 'medical';
  }
  if (ABSOLUTE_TERMS.includes(term)) {
    return 'absolute';
  }
  // 兜底：词表快照未收录但含医学语素 → 保守视为疾病宣称，其余视为绝对化
  if (
    /治疗|疗效|治病|痊愈|根治|降压|降糖|降脂|抗癌|防癌|消炎|杀菌|祛湿|排毒|补肾|壮阳|延年益寿/.test(
      term,
    )
  ) {
    return 'medical';
  }
  return 'absolute';
}

export interface RiskGroups {
  absolute: string[];
  medical: string[];
}

/** 按类别分组敏感词（保持去重、保留返回顺序） */
export function partitionRiskTerms(terms: string[]): RiskGroups {
  const groups: RiskGroups = { absolute: [], medical: [] };
  for (const t of terms) {
    (classifyTerm(t) === 'medical' ? groups.medical : groups.absolute).push(t);
  }
  return groups;
}
