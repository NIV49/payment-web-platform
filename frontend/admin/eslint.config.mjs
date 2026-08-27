import { defineConfig } from '@vben/eslint-config';

const noApplicationAlias = {
  message:
    'Shared runtime code must use @payment/backoffice-runtime package self-references.',
  regex: '^#/',
};

export default defineConfig([
  {
    files: ['packages/effects/backoffice-runtime/src/**/*.{ts,tsx,vue}'],
    rules: {
      'no-restricted-imports': ['error', { patterns: [noApplicationAlias] }],
    },
  },
  {
    files: [
      'packages/effects/backoffice-runtime/src/views/system/**/*.{ts,tsx,vue}',
    ],
    rules: {
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            noApplicationAlias,
            {
              message:
                'Shared system views must use package self-references for cross-module imports.',
              regex: '^(?:\\.\\./){3,}',
            },
          ],
        },
      ],
    },
  },
]);
