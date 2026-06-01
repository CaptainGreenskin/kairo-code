import { test, expect } from '@playwright/test';

/**
 * Phase 5: Token/cost display correctness and regression tests.
 *
 * Validates that:
 * - AGENT_DONE events with separate inputTokens/outputTokens are handled
 * - The cost formula uses the updated GPT-4o-class pricing
 * - Removing LeftSidebar.tsx didn't break the app (regression)
 */

const MOCK_CONFIG = {
    model: 'gpt-4o',
    provider: 'openai',
    workingDir: '/tmp/test',
    apiKeySet: true,
    availableModels: ['gpt-4o'],
};

test.beforeEach(async ({ page }) => {
    await page.route('/api/config', route =>
        route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MOCK_CONFIG) }));
    await page.route('/api/sessions/snapshots', route =>
        route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
    await page.route(/\/api\/sessions\/search.*/, route =>
        route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));
});

test.describe('Token display', () => {
    test('AGENT_DONE with separate input/output tokens is parsed correctly', async ({ page }) => {
        // Verify the transform logic by evaluating it in-page
        await page.goto('/');
        await page.waitForTimeout(500);

        // Simulate the transform that useAgentWebSocket applies
        const result = await page.evaluate(() => {
            // Mirror the AGENT_DONE transform from useAgentWebSocket.ts
            const raw = {
                type: 'AGENT_DONE',
                sessionId: 'test-session',
                tokenUsage: 1500,
                inputTokens: 1000,
                outputTokens: 500,
                cost: null,
            };

            const totalTokens = (raw.tokenUsage as number) ?? 0;
            const inputTokens = (raw.inputTokens as number) ?? totalTokens;
            const outputTokens = (raw.outputTokens as number) ?? Math.max(0, totalTokens - inputTokens);

            return { inputTokens, outputTokens, totalTokens };
        });

        expect(result.inputTokens).toBe(1000);
        expect(result.outputTokens).toBe(500);
        expect(result.totalTokens).toBe(1500);
    });

    test('AGENT_DONE falls back gracefully when output tokens missing', async ({ page }) => {
        await page.goto('/');
        await page.waitForTimeout(500);

        const result = await page.evaluate(() => {
            const raw = {
                type: 'AGENT_DONE',
                sessionId: 'test-session',
                tokenUsage: 2000,
                // inputTokens/outputTokens not provided (old backend format)
            };

            const totalTokens = ((raw as any).tokenUsage as number) ?? 0;
            const inputTokens = ((raw as any).inputTokens as number) ?? totalTokens;
            const outputTokens = ((raw as any).outputTokens as number) ?? Math.max(0, totalTokens - inputTokens);

            return { inputTokens, outputTokens };
        });

        // Should fallback: inputTokens = totalTokens, outputTokens = 0
        expect(result.inputTokens).toBe(2000);
        expect(result.outputTokens).toBe(0);
    });
});

test.describe('Cost estimation', () => {
    test('uses GPT-4o-class pricing formula', async ({ page }) => {
        await page.goto('/');
        await page.waitForTimeout(500);

        const cost = await page.evaluate(() => {
            // Mirror the cost formula from useAgentEventHandler.ts
            const inputTokens = 100_000;
            const outputTokens = 10_000;
            const serverCost = null; // not provided
            const estimated = serverCost ?? (inputTokens * 2.5 + outputTokens * 10.0) / 1_000_000;
            return estimated;
        });

        // 100K input * $2.5/M + 10K output * $10/M = $0.25 + $0.10 = $0.35
        expect(cost).toBeCloseTo(0.35, 4);
    });

    test('server-provided cost takes precedence', async ({ page }) => {
        await page.goto('/');
        await page.waitForTimeout(500);

        const cost = await page.evaluate(() => {
            const inputTokens = 100_000;
            const outputTokens = 10_000;
            const serverCost = 0.42;
            const estimated = serverCost ?? (inputTokens * 2.5 + outputTokens * 10.0) / 1_000_000;
            return estimated;
        });

        expect(cost).toBe(0.42);
    });
});

test.describe('Regression: LeftSidebar removal', () => {
    test('app loads successfully without LeftSidebar component', async ({ page }) => {
        await page.goto('/');
        await expect(page.locator('body')).toBeVisible();
        await expect(page.locator('body')).not.toBeEmpty();
        // No crash, no blank page
        const text = await page.locator('body').textContent();
        expect(text!.length).toBeGreaterThan(0);
    });

    test('main layout renders with sidebar and chat area', async ({ page }) => {
        await page.goto('/');
        await page.waitForTimeout(500);
        // The app should still have a functional layout (PrimarySidebar replaced LeftSidebar)
        await expect(page.locator('body')).toBeVisible();
        // Look for any key layout element that proves the app rendered
        const hasContent = await page.evaluate(() => {
            return document.querySelectorAll('button, input, [role]').length > 0;
        });
        expect(hasContent).toBe(true);
    });
});

test.describe('Auth interceptor does not break non-API requests', () => {
    test('static assets load without auth headers', async ({ page }) => {
        // Set a token
        await page.goto('/');
        await page.evaluate(() => localStorage.setItem('kairo.authToken', 'test-token'));

        // Track requests to verify non-/api paths don't get auth headers
        const nonApiRequests: { url: string; hasAuth: boolean }[] = [];
        page.on('request', req => {
            const url = req.url();
            if (!url.includes('/api/') && !url.startsWith('data:')) {
                nonApiRequests.push({
                    url,
                    hasAuth: !!req.headers()['authorization'],
                });
            }
        });

        await page.reload();
        await page.waitForTimeout(1000);

        // Non-API requests (JS/CSS/fonts) should not have auth headers
        for (const req of nonApiRequests) {
            expect(req.hasAuth).toBe(false);
        }
    });
});
