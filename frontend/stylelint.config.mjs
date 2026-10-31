export default {
  extends: ['@vben/stylelint-config'],
  ignoreFiles: [
    '**/*.js',
    '**/*.jsx',
    '**/*.tsx',
    '**/*.ts',
    '**/*.json',
    '**/*.md',
    '**/dist/**',
    '**/node_modules/**',
    '**/.turbo/**',
    '**/.output/**',
  ],
  root: true,
  rules: {
    // Tailwind v4 的 @apply 会被 stylelint 17 的 prelude 规则误判。
    // 上游配置只放行了 at-rule 名称，没有关掉这条。
    'at-rule-prelude-no-invalid': null,
  },
};
