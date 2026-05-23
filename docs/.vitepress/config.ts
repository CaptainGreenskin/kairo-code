import { defineConfig } from 'vitepress';

export default defineConfig({
  title: 'Kairo Code',
  description: 'Same Models. Governable. — A Java Code Agent built on Kairo.',
  lang: 'en-US',
  cleanUrls: true,
  lastUpdated: true,

  themeConfig: {
    nav: [
      { text: 'Guide', link: '/guide/quickstart' },
      { text: 'Reference', link: '/reference/commands' },
      { text: 'Changelog', link: '/changelog' },
      { text: 'Kairo (upstream)', link: 'https://github.com/captaingreenskin/kairo' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Get started',
          collapsed: false,
          items: [
            { text: 'Quick start', link: '/guide/quickstart' },
            { text: 'Installation', link: '/guide/install' },
            { text: 'Configuration', link: '/guide/configuration' },
          ],
        },
        {
          text: 'Concepts',
          collapsed: false,
          items: [
            { text: 'REPL workflow', link: '/guide/repl' },
            { text: 'Skills', link: '/guide/skills' },
            { text: 'Plugins', link: '/guide/plugins' },
            { text: 'Hooks', link: '/guide/hooks' },
            { text: 'Expert team', link: '/guide/expert-team' },
          ],
        },
        {
          text: 'Operations',
          collapsed: false,
          items: [
            { text: 'PII redaction', link: '/guide/pii' },
            { text: 'Observability (OTLP)', link: '/guide/observability' },
            { text: 'Troubleshooting', link: '/guide/troubleshooting' },
          ],
        },
        {
          text: 'Embedding',
          collapsed: false,
          items: [
            { text: 'Java SDK', link: '/guide/sdk' },
          ],
        },
      ],
      '/reference/': [
        { text: 'Commands reference', link: '/reference/commands' },
        { text: 'Tools reference', link: '/reference/tools' },
        { text: 'Environment variables', link: '/reference/env' },
        { text: 'M3 Task tool demo', link: '/m3-task-tool-demo' },
      ],
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/captaingreenskin/kairo-code' },
    ],

    footer: {
      message: 'Released under the Apache 2.0 License.',
      copyright: 'Copyright © 2025–2026 the Kairo authors.',
    },
  },
});
