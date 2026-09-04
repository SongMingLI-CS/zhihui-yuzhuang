import type { Config } from 'tailwindcss';

/**
 * 智汇于庄 web · Tailwind 主题
 * 设计语言：党政治理绿 + 产业金；数据大屏深底浅字可选（本版采用浅色治理后台风格）。
 */
const config: Config = {
  content: ['./src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#f0f9f2',
          100: '#dcf0e1',
          200: '#bce0c7',
          300: '#8fc9a2',
          400: '#5dab79',
          500: '#3b8f5c',
          600: '#2c724a',
          700: '#245b3d',
          800: '#1f4933',
          900: '#1a3c2b',
        },
        gold: {
          400: '#e9b949',
          500: '#d9a52e',
          600: '#bd8a1f',
        },
      },
      fontFamily: {
        sans: [
          '-apple-system',
          'BlinkMacSystemFont',
          'PingFang SC',
          'Hiragino Sans GB',
          'Microsoft YaHei',
          'Helvetica Neue',
          'sans-serif',
        ],
        digit: ['SF Mono', 'Menlo', 'Consolas', 'Roboto Mono', 'monospace'],
      },
      keyframes: {
        'fade-in': {
          from: { opacity: '0' },
          to: { opacity: '1' },
        },
        'slide-up': {
          from: { opacity: '0', transform: 'translateY(16px)' },
          to: { opacity: '1', transform: 'translateY(0)' },
        },
        'slide-in-right': {
          from: { opacity: '0', transform: 'translateX(100%)' },
          to: { opacity: '1', transform: 'translateX(0)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
        blink: {
          '0%,100%': { opacity: '1' },
          '50%': { opacity: '0.25' },
        },
        'pulse-soft': {
          '0%,100%': { transform: 'scale(1)', opacity: '1' },
          '50%': { transform: 'scale(1.35)', opacity: '0.6' },
        },
      },
      animation: {
        'fade-in': 'fade-in 0.25s ease-out both',
        'slide-up': 'slide-up 0.3s cubic-bezier(0.16,1,0.3,1) both',
        'slide-in-right': 'slide-in-right 0.3s cubic-bezier(0.16,1,0.3,1) both',
        shimmer: 'shimmer 1.6s linear infinite',
        blink: 'blink 1.1s ease-in-out infinite',
        'pulse-soft': 'pulse-soft 1.4s ease-in-out infinite',
      },
    },
  },
  plugins: [],
};

export default config;
