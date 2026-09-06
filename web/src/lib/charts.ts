/**
 * 智汇于庄 web · ECharts 按需注册
 *
 * 替代全量 `import * as echarts from 'echarts'`（整包约 1MB），
 * 仅注册项目中实际用到的折线图 / 环形图及配套组件，
 * 可将图表依赖体积压缩 60%+。
 */
import * as echarts from 'echarts/core';

// 图表
import { LineChart, PieChart } from 'echarts/charts';
// 组件
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
// 特性与渲染器
import { LabelLayout, UniversalTransition } from 'echarts/features';
import { CanvasRenderer } from 'echarts/renderers';

echarts.use([
  LineChart,
  PieChart,
  GridComponent,
  LegendComponent,
  TooltipComponent,
  LabelLayout,
  UniversalTransition,
  CanvasRenderer,
]);

export default echarts;
