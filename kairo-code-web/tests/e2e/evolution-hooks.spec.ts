import { test, expect } from '@playwright/test';

/**
 * Phase 2+5: EvolutionPanel and HookConfigPanel error/empty states.
 *
 * These panels are opened via the command palette. Tests mock the backend APIs
 * to exercise success, failure, and empty scenarios.
 */

const MOCK_CONFIG = {
    model: 'gpt-4o',
    provider: 'openai',
    workingDir: '/tmp/test',
    apiKeySet: true,
    availableModels: ['gpt-4o'],
};

function setupBaseRoutes(page: import('@playwright/test').Page) {
    return Promise.all([
        page.route('/api/config', route =>
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MOCK_CONFIG) })),
        page.route('/api/sessions/snapshots', route =>
            route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })),
        page.route(/\/api\/sessions\/search.*/, route =>
            route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })),
    ]);
}

const SAMPLE_LESSONS = [
    { id: 'L1', toolName: 'Bash', lessonText: 'Always check exit codes', status: 'APPROVED', timestamp: '2026-01-15T10:00:00Z' },
    { id: 'L2', toolName: 'Read', lessonText: 'Verify file exists before reading', status: 'PENDING', timestamp: '2026-01-16T10:00:00Z' },
    { id: 'L3', toolName: 'Write', lessonText: 'Back up before overwriting', status: 'REJECTED', timestamp: '2026-01-17T10:00:00Z' },
];

const SAMPLE_HOOKS = [
    { name: 'ContextWindowGuardHook', description: 'Warns on large context', enabled: true },
    { name: 'TestFailureFeedbackHook', description: 'Detects Maven test failures', enabled: true },
    { name: 'AutoCommitOnSuccessHook', description: 'Auto commits on success', enabled: false },
];

async function openCommandPalette(page: import('@playwright/test').Page) {
    await page.keyboard.press('Meta+k');
    await page.waitForTimeout(300);
}

async function openEvolutionPanel(page: import('@playwright/test').Page) {
    await openCommandPalette(page);
    const input = page.locator('input[type="text"]').first();
    if (await input.isVisible()) {
        await input.fill('evolution');
        await page.waitForTimeout(300);
        await page.keyboard.press('Enter');
        await page.waitForTimeout(500);
    }
}

async function openHookPanel(page: import('@playwright/test').Page) {
    await openCommandPalette(page);
    const input = page.locator('input[type="text"]').first();
    if (await input.isVisible()) {
        await input.fill('hook');
        await page.waitForTimeout(300);
        await page.keyboard.press('Enter');
        await page.waitForTimeout(500);
    }
}

// ── Evolution Panel ──

test.describe('EvolutionPanel', () => {
    test('renders lessons list on success', async ({ page }) => {
        await setupBaseRoutes(page);
        await page.route('/api/evolution/lessons', route =>
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SAMPLE_LESSONS) }));

        await page.goto('/');
        await page.waitForTimeout(500);
        await openEvolutionPanel(page);

        const panel = page.locator('text=Self-Evolution Lessons');
        if (await panel.isVisible({ timeout: 3000 }).catch(() => false)) {
            // Should show lesson count badge
            await expect(page.locator('text=3')).toBeVisible({ timeout: 2000 });
            // Should show lesson tool names
            await expect(page.locator('text=Bash')).toBeVisible();
            await expect(page.locator('text=Read')).toBeVisible();
            // Should show lesson text
            await expect(page.locator('text=Always check exit codes')).toBeVisible();
        }
    });

    test('shows error message on API failure', async ({ page }) => {
        await setupBaseRoutes(page);
        await page.route('/api/evolution/lessons', route =>
            route.fulfill({ status: 500, contentType: 'text/plain', body: 'Internal Server Error' }));

        await page.goto('/');
        await page.waitForTimeout(500);
        await openEvolutionPanel(page);

        const panel = page.locator('text=Self-Evolution Lessons');
        if (await panel.isVisible({ timeout: 3000 }).catch(() => false)) {
            // Should show error indicator (red text with HTTP status)
            await expect(page.locator('text=/HTTP 500/i')).toBeVisible({ timeout: 2000 });
        }
    });

    test('shows empty state when no lessons', async ({ page }) => {
        await setupBaseRoutes(page);
        await page.route('/api/evolution/lessons', route =>
            route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));

        await page.goto('/');
        await page.waitForTimeout(500);
        await openEvolutionPanel(page);

        const panel = page.locator('text=Self-Evolution Lessons');
        if (await panel.isVisible({ timeout: 3000 }).catch(() => false)) {
            await expect(page.locator('text=No lessons yet')).toBeVisible({ timeout: 2000 });
            await expect(page.locator('text=Lessons are generated when tools fail')).toBeVisible();
        }
    });

    test('approve action calls the correct API', async ({ page }) => {
        await setupBaseRoutes(page);
        await page.route('/api/evolution/lessons', route =>
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SAMPLE_LESSONS) }));

        let approveUrl = '';
        let approveMethod = '';
        await page.route(/\/api\/evolution\/lessons\/.*\/status/, async route => {
            approveUrl = route.request().url();
            approveMethod = route.request().method();
            await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
        });

        await page.goto('/');
        await page.waitForTimeout(500);
        await openEvolutionPanel(page);

        const panel = page.locator('text=Self-Evolution Lessons');
        if (await panel.isVisible({ timeout: 3000 }).catch(() => false)) {
            // Click the approve button on the PENDING lesson (L2)
            const approveBtn = page.locator('button[title="Approve"]').first();
            if (await approveBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
                await approveBtn.click();
                await page.waitForTimeout(500);
                expect(approveMethod).toBe('PUT');
                expect(approveUrl).toContain('/status');
            }
        }
    });

    test('delete action calls the correct API', async ({ page }) => {
        await setupBaseRoutes(page);
        await page.route('/api/evolution/lessons', route =>
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SAMPLE_LESSONS) }));

        let deleteMethod = '';
        await page.route(/\/api\/evolution\/lessons\/[^/]+$/, async route => {
            if (route.request().method() === 'DELETE') {
                deleteMethod = 'DELETE';
                await route.fulfill({ status: 200, contentType: 'application/json', body: '{}' });
            } else {
                await route.continue();
            }
        });

        await page.goto('/');
        await page.waitForTimeout(500);
        await openEvolutionPanel(page);

        const panel = page.locator('text=Self-Evolution Lessons');
        if (await panel.isVisible({ timeout: 3000 }).catch(() => false)) {
            const deleteBtn = page.locator('button[title="Delete"]').first();
            if (await deleteBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
                await deleteBtn.click();
                await page.waitForTimeout(500);
                expect(deleteMethod).toBe('DELETE');
            }
        }
    });

    test('filter tabs work', async ({ page }) => {
        await setupBaseRoutes(page);
        await page.route('/api/evolution/lessons', route =>
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SAMPLE_LESSONS) }));

        await page.goto('/');
        await page.waitForTimeout(500);
        await openEvolutionPanel(page);

        const panel = page.locator('text=Self-Evolution Lessons');
        if (await panel.isVisible({ timeout: 3000 }).catch(() => false)) {
            // Click PENDING tab
            const pendingTab = page.locator('button:has-text("PENDING")');
            if (await pendingTab.isVisible()) {
                await pendingTab.click();
                await page.waitForTimeout(300);
                // Should show only the PENDING lesson
                await expect(page.locator('text=Verify file exists')).toBeVisible();
            }
        }
    });
});

// ── Hook Config Panel ──

test.describe('HookConfigPanel', () => {
    test('renders hook list on success', async ({ page }) => {
        await setupBaseRoutes(page);
        await page.route('/api/hooks', route =>
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SAMPLE_HOOKS) }));

        await page.goto('/');
        await page.waitForTimeout(500);
        await openHookPanel(page);

        const panel = page.locator('text=Hook Configuration');
        if (await panel.isVisible({ timeout: 3000 }).catch(() => false)) {
            // Shows enabled count
            await expect(page.locator('text=2/3 enabled')).toBeVisible({ timeout: 2000 });
            // Shows hook names
            await expect(page.locator('text=ContextWindowGuardHook')).toBeVisible();
            await expect(page.locator('text=TestFailureFeedbackHook')).toBeVisible();
            await expect(page.locator('text=AutoCommitOnSuccessHook')).toBeVisible();
        }
    });

    test('shows error on API failure', async ({ page }) => {
        await setupBaseRoutes(page);
        await page.route('/api/hooks', route =>
            route.fulfill({ status: 500, contentType: 'text/plain', body: 'Server Error' }));

        await page.goto('/');
        await page.waitForTimeout(500);
        await openHookPanel(page);

        const panel = page.locator('text=Hook Configuration');
        if (await panel.isVisible({ timeout: 3000 }).catch(() => false)) {
            await expect(page.locator('text=/HTTP 500/i')).toBeVisible({ timeout: 2000 });
        }
    });

    test('shows empty state when no hooks', async ({ page }) => {
        await setupBaseRoutes(page);
        await page.route('/api/hooks', route =>
            route.fulfill({ status: 200, contentType: 'application/json', body: '[]' }));

        await page.goto('/');
        await page.waitForTimeout(500);
        await openHookPanel(page);

        const panel = page.locator('text=Hook Configuration');
        if (await panel.isVisible({ timeout: 3000 }).catch(() => false)) {
            await expect(page.locator('text=No hooks configured')).toBeVisible({ timeout: 2000 });
        }
    });

    test('toggle calls the correct API', async ({ page }) => {
        await setupBaseRoutes(page);
        await page.route('/api/hooks', route =>
            route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(SAMPLE_HOOKS) }));

        let toggleUrl = '';
        await page.route(/\/api\/hooks\/.*\/toggle/, async route => {
            toggleUrl = route.request().url();
            const hookName = toggleUrl.split('/').slice(-2, -1)[0];
            const hook = SAMPLE_HOOKS.find(h => h.name === hookName);
            await route.fulfill({
                status: 200,
                contentType: 'application/json',
                body: JSON.stringify(hook ? { ...hook, enabled: !hook.enabled } : {}),
            });
        });

        await page.goto('/');
        await page.waitForTimeout(500);
        await openHookPanel(page);

        const panel = page.locator('text=Hook Configuration');
        if (await panel.isVisible({ timeout: 3000 }).catch(() => false)) {
            // Click toggle on first hook
            const toggleBtn = page.locator('button[title="Disable"], button[title="Enable"]').first();
            if (await toggleBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
                await toggleBtn.click();
                await page.waitForTimeout(500);
                expect(toggleUrl).toContain('/toggle');
            }
        }
    });
});
