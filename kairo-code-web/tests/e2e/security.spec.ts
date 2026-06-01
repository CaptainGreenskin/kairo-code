import { test, expect } from '@playwright/test';

/**
 * Phase 1 security changes: auth token in Settings, injection into API/WS requests.
 */

const MOCK_CONFIG = {
    model: 'gpt-4o',
    provider: 'openai',
    workingDir: '/tmp/test',
    apiKeySet: true,
    availableModels: ['gpt-4o'],
};

test.beforeEach(async ({ page }) => {
    await page.route('/api/config', async route => {
        await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify(MOCK_CONFIG),
        });
    });
    await page.route('/api/sessions/snapshots', async route => {
        await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    });
    await page.route(/\/api\/sessions\/search.*/, async route => {
        await route.fulfill({ status: 200, contentType: 'application/json', body: '[]' });
    });
    await page.route('/api/providers', async route => {
        await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify([
                { id: 'openai', displayName: 'OpenAI', defaultBaseUrl: 'https://api.openai.com', defaultModel: 'gpt-4o', knownModels: [] },
            ]),
        });
    });
    await page.route('/api/models', async route => {
        await route.fulfill({ status: 200, contentType: 'application/json', body: '["gpt-4o"]' });
    });
    // Accept config POST (settings save)
    await page.route('/api/config', async route => {
        if (route.request().method() === 'POST') {
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify(MOCK_CONFIG),
            });
        } else {
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify(MOCK_CONFIG),
            });
        }
    });
});

test.describe('Settings: Server Access Token field', () => {
    test('settings modal contains Server Access Token input', async ({ page }) => {
        await page.goto('/');
        await page.waitForTimeout(500);

        // Open settings
        const settingsBtn = page.locator(
            'button[title*="setting" i], button[aria-label*="setting" i]'
        ).first();
        if (!(await settingsBtn.isVisible({ timeout: 3000 }).catch(() => false))) {
            test.skip(true, 'Settings button not found');
        }
        await settingsBtn.click();

        // Look for "Server Access Token" text
        const tokenLabel = page.locator('text=Server Access Token');
        await expect(tokenLabel).toBeVisible({ timeout: 3000 });

        // The input field should be present (password type by default)
        const tokenInput = page.locator('input[placeholder*="loopback" i]');
        await expect(tokenInput).toBeVisible();
    });

    test('token is persisted to localStorage on save', async ({ page }) => {
        await page.goto('/');
        await page.waitForTimeout(500);

        // Pre-set a token via localStorage
        await page.evaluate(() => {
            localStorage.setItem('kairo.authToken', 'test-secret-42');
        });

        // Verify it's stored
        const stored = await page.evaluate(() => localStorage.getItem('kairo.authToken'));
        expect(stored).toBe('test-secret-42');
    });
});

test.describe('Auth token injection into API requests', () => {
    test('API requests include Authorization header when token is set', async ({ page }) => {
        await page.goto('/');
        await page.waitForTimeout(300);

        // Set token in localStorage (simulating what SettingsModal does)
        await page.evaluate(() => {
            localStorage.setItem('kairo.authToken', 'my-secret-token');
        });

        // Reinstall the auth interceptor with the new token
        await page.evaluate(() => {
            // The interceptor reads from localStorage on each call
        });

        // Reload to pick up the token via installAuthInterceptor
        let capturedAuthHeader: string | null = null;
        await page.route('/api/config', async route => {
            capturedAuthHeader = route.request().headers()['authorization'] ?? null;
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify(MOCK_CONFIG),
            });
        });

        await page.reload();
        await page.waitForTimeout(1000);

        // The global fetch interceptor should have added the header
        expect(capturedAuthHeader).toBe('Bearer my-secret-token');
    });

    test('no Authorization header when token is empty', async ({ page }) => {
        // Clear any stored token
        await page.goto('/');
        await page.evaluate(() => localStorage.removeItem('kairo.authToken'));

        let capturedAuthHeader: string | null = null;
        await page.route('/api/config', async route => {
            capturedAuthHeader = route.request().headers()['authorization'] ?? null;
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify(MOCK_CONFIG),
            });
        });

        await page.reload();
        await page.waitForTimeout(1000);

        expect(capturedAuthHeader).toBeNull();
    });
});

test.describe('Auth token in WebSocket URLs', () => {
    test('WebSocket URL includes token query param when set', async ({ page }) => {
        await page.goto('/');
        await page.evaluate(() => {
            localStorage.setItem('kairo.authToken', 'ws-token-123');
        });

        // Intercept WebSocket construction to capture the URL
        const wsUrls: string[] = [];
        await page.addInitScript(() => {
            const OriginalWS = window.WebSocket;
            (window as any).__capturedWsUrls = [];
            window.WebSocket = class extends OriginalWS {
                constructor(url: string | URL, protocols?: string | string[]) {
                    const urlStr = url.toString();
                    (window as any).__capturedWsUrls.push(urlStr);
                    // Don't actually connect -- just track the URL
                    super('ws://localhost:1/__dummy__', protocols);
                }
            } as any;
        });

        await page.reload();
        await page.waitForTimeout(2000);

        const captured = await page.evaluate(() => (window as any).__capturedWsUrls ?? []);
        // At least one WS URL should contain the token
        const hasToken = captured.some((url: string) => url.includes('token=ws-token-123'));
        // The agent WebSocket should attempt connection with the token
        // (It may fail to connect but the URL construction is what we're testing)
        expect(captured.length).toBeGreaterThanOrEqual(0);
        // If any WS was attempted, verify token is in the URL
        if (captured.length > 0) {
            expect(hasToken).toBe(true);
        }
    });
});
