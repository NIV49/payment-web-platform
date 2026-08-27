#!/usr/bin/env node

const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const { parseEnvironment } = require('./verify_iam002_local.cjs');

const REPOSITORY_ROOT = path.resolve(__dirname, '../..');
const ENV_PATH = path.join(REPOSITORY_ROOT, '.local/iam002/runtime.env');
const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const DELEGATED_ADMINISTRATION_CODES = [
  'department:view',
  'menu:view',
  'role:create',
  'role:delete',
  'role:grant-update',
  'role:update',
  'role:view',
  'user:assign-role',
  'user:create',
  'user:delete',
  'user:disable',
  'user:update',
  'user:view',
];

const PORTALS = [
  {
    cookie: 'PAYMENT_PLATFORM_SESSION',
    domain: 'PLATFORM',
    displayName: 'Platform Administrator',
    origin: 'http://platform.localhost:15999',
    username: 'admin@platform.localhost',
  },
  {
    cookie: 'PAYMENT_MERCHANT_SESSION',
    domain: 'MERCHANT',
    displayName: 'Merchant Administrator',
    origin: 'http://merchant-e2e.localhost:16002',
    username: 'admin@merchant.localhost',
  },
  {
    cookie: 'PAYMENT_AGENT_SESSION',
    domain: 'AGENT',
    displayName: 'Agent Administrator',
    origin: 'http://agent.localhost:16001',
    username: 'admin@agent.localhost',
  },
];

class VerificationError extends Error {}

function temporaryLocalPassword() {
  return `Aa1${crypto.randomBytes(6).toString('hex')}!!!!`;
}

async function getApi(page, route) {
  return page.evaluate(async (url) => {
    const response = await fetch(url);
    let body = null;
    try { body = await response.json(); } catch {}
    return { body, status: response.status };
  }, route);
}

async function postApi(page, route, body) {
  return page.evaluate(async ({ requestBody, url }) => {
    const csrfResponse = await fetch('/api/auth/csrf');
    const csrfBody = await csrfResponse.json();
    const response = await fetch(url, {
      body: JSON.stringify(requestBody),
      headers: {
        'Content-Type': 'application/json',
        'X-CSRF-Token': csrfBody.data.requestProof,
      },
      method: 'POST',
    });
    let responseBody = null;
    try { responseBody = await response.json(); } catch {}
    return { body: responseBody, status: response.status };
  }, { requestBody: body, url: route });
}

async function verifyPasswordLogin(browser, portal, password) {
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(`${portal.origin}/auth/login`, { waitUntil: 'networkidle' });
    await page.locator('input[name="username"]').fill(portal.username);
    await page.locator('input[name="password"]').fill(password);
    await page.locator('button[aria-label="login"]').click();
    await page.waitForURL((url) => (
      url.origin === portal.origin && url.pathname !== '/auth/login'
    ), { timeout: 15_000, waitUntil: 'domcontentloaded' });
    const response = await page.request.get(`${portal.origin}/api/auth/codes`);
    if (response.status() !== 200) {
      throw new VerificationError(`${portal.domain} rejected the reset password`);
    }
  } finally {
    await context.close();
  }
}

async function verifyPlatformPasswordReset(browser, platformPage, portal) {
  const query = new URLSearchParams({
    accountDomain: portal.domain,
    page: '1',
    pageSize: '20',
    username: portal.username,
  });
  const directory = await getApi(platformPage, `/api/platform/user-directory?${query}`);
  const targets = directory.body?.data?.items?.filter(
    (item) => item.accountDomain === portal.domain && item.username === portal.username,
  );
  if (directory.status !== 200 || !Array.isArray(targets) || targets.length !== 1) {
    throw new VerificationError(`PLATFORM could not resolve the ${portal.domain} reset target`);
  }

  const target = targets[0];
  const temporaryPassword = temporaryLocalPassword();
  const resetRoute = `/api/platform/users/${encodeURIComponent(target.id)}/password/reset`;
  const reset = await postApi(platformPage, resetRoute, {
    accountDomain: portal.domain,
    credentialVersion: target.credentialVersion,
    password: temporaryPassword,
    tenantId: target.tenantId,
  });
  if (reset.status !== 200 || !Number.isInteger(reset.body?.data?.credentialVersion)) {
    throw new VerificationError(`PLATFORM could not reset the ${portal.domain} password`);
  }

  await verifyPasswordLogin(browser, portal, temporaryPassword);
  console.log(`PASS PLATFORM reset and verified ${portal.domain} local password`);
}

function restoreFixtureLocalPasswords() {
  execFileSync(process.env.PYTHON || 'python3', [
    '-c',
    "import runpy; ns=runpy.run_path('scripts/dev/iam002_local.py'); "
      + "ns['_synchronize_local_password_login'](ns['_read_private_environment'](), 'local')",
  ], {
    cwd: REPOSITORY_ROOT,
    stdio: 'ignore',
  });
}

async function verifyPortal(context, portal, password) {
  const page = await context.newPage();
  const consoleErrors = [];
  const failedApiResponses = [];
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });
  page.on('pageerror', (error) => consoleErrors.push(error.message));
  page.on('response', (response) => {
    if (response.url().includes('/api/') && response.status() >= 400) {
      failedApiResponses.push(`${response.status()} ${new URL(response.url()).pathname}`);
    }
  });
  await page.goto(`${portal.origin}/auth/login`, { waitUntil: 'networkidle' });
  const username = page.locator('input[name="username"]');
  const passwordField = page.locator('input[name="password"]');
  if (await username.count() !== 1 || await passwordField.count() !== 1) {
    throw new VerificationError(`${portal.domain} did not render the local login form`);
  }
  assert.equal(await username.inputValue(), portal.username);
  assert.equal(await passwordField.inputValue(), password);
  await page.locator('button[aria-label="login"]').click();
  await page.waitForURL((url) => (
    url.origin === portal.origin && url.pathname !== '/auth/login'
  ), { timeout: 15_000, waitUntil: 'domcontentloaded' });
  const protectedResponse = await page.request.get(`${portal.origin}/api/auth/codes`);
  if (protectedResponse.status() !== 200) {
    throw new VerificationError(`${portal.domain} local session is not usable`);
  }
  const codesPayload = await protectedResponse.json();
  if (!Array.isArray(codesPayload.data)) {
    throw new VerificationError(`${portal.domain} returned an invalid permission code response`);
  }
  const grantedCodes = new Set(codesPayload.data);
  const missingCodes = DELEGATED_ADMINISTRATION_CODES.filter(
    (code) => !grantedCodes.has(code),
  );
  if (missingCodes.length) {
    throw new VerificationError(
      `${portal.domain} is missing delegated administration codes: ${missingCodes.join(', ')}`,
    );
  }
  await page.goto(`${portal.origin}/system/user`, { waitUntil: 'networkidle' });
  if (await page.getByText('用户列表', { exact: true }).count() !== 1) {
    throw new VerificationError(`${portal.domain} user management page did not render`);
  }
  if (await page.getByText(portal.displayName, { exact: true }).count() !== 1) {
    throw new VerificationError(`${portal.domain} current administrator did not render`);
  }
  await page.goto(`${portal.origin}/system/role`, { waitUntil: 'networkidle' });
  if (await page.getByText('角色列表', { exact: true }).count() !== 1) {
    throw new VerificationError(`${portal.domain} role management page did not render`);
  }
  if (failedApiResponses.length) {
    throw new VerificationError(
      `${portal.domain} administration API failed: ${failedApiResponses.join(', ')}`,
    );
  }
  if (consoleErrors.length) {
    throw new VerificationError(`${portal.domain} emitted a browser error after local login`);
  }
  console.log(`PASS ${portal.domain} prefilled local password login`);
  await page.close();
}

async function run() {
  if (!fs.existsSync(CHROME_PATH)) {
    throw new VerificationError('Google Chrome is required for local browser verification');
  }
  const env = parseEnvironment(fs.readFileSync(ENV_PATH, 'utf8'));
  const { chromium } = require(path.join(
    REPOSITORY_ROOT, 'frontend/admin/node_modules/playwright',
  ));
  const browser = await chromium.launch({ executablePath: CHROME_PATH, headless: true });
  const context = await browser.newContext();
  try {
    for (const portal of PORTALS) {
      const password = env[`PAYMENT_${portal.domain}_LOCAL_ADMIN_PASSWORD`];
      if (!password) throw new VerificationError(`${portal.domain} local password is unavailable`);
      await verifyPortal(context, portal, password);
    }
    const cookies = await context.cookies(PORTALS.map((portal) => portal.origin));
    for (const portal of PORTALS) {
      const matching = cookies.filter((cookie) => cookie.name === portal.cookie);
      if (matching.length !== 1 || matching[0].domain !== new URL(portal.origin).hostname
          || !matching[0].httpOnly || matching[0].sameSite !== 'Strict') {
        throw new VerificationError(`${portal.domain} local Cookie boundary is invalid`);
      }
    }
    console.log('PASS three local host-only Cookie boundaries');
    const platformPage = await context.newPage();
    await platformPage.goto(PORTALS[0].origin, { waitUntil: 'domcontentloaded' });
    try {
      for (const portal of PORTALS.slice(1)) {
        await verifyPlatformPasswordReset(browser, platformPage, portal);
      }
    } finally {
      try {
        restoreFixtureLocalPasswords();
        for (const portal of PORTALS.slice(1)) {
          await verifyPasswordLogin(
            browser,
            portal,
            env[`PAYMENT_${portal.domain}_LOCAL_ADMIN_PASSWORD`],
          );
        }
        console.log('PASS local fixture passwords restored after cross-domain reset verification');
      } finally {
        await platformPage.close();
      }
    }
    console.log('IAM-002 local password browser verification passed');
  } finally {
    await context.close().catch(() => {});
    await browser.close().catch(() => {});
  }
}

if (require.main === module) {
  run().catch((error) => {
    console.error(error instanceof VerificationError
      ? error.message
      : 'IAM-002 local password browser verification failed unexpectedly');
    process.exitCode = 1;
  });
}

module.exports = { PORTALS, temporaryLocalPassword };
