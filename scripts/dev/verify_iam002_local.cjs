#!/usr/bin/env node

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const REPOSITORY_ROOT = path.resolve(__dirname, '../..');
const PRIVATE_ROOT = path.join(REPOSITORY_ROOT, '.local/iam002');
const ENV_PATH = path.join(PRIVATE_ROOT, 'runtime.env');
const STATE_PATH = path.join(PRIVATE_ROOT, 'e2e-state.json');
const LEGACY_STATE_PATH = path.join(PRIVATE_ROOT, 'e2e-identity.json');
const COMPOSE_PATH = path.join(REPOSITORY_ROOT, 'infra/docker-compose.iam002-local.yml');
const CHROME_PATH = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const KEYCLOAK_ORIGIN = 'http://127.0.0.1:18080';
const MERCHANT_BOOTSTRAP_IDEMPOTENCY_KEY = 'c0f7e9d5-e862-42b6-8c68-acff7fb74482';
const MERCHANT_MEMBER_IDEMPOTENCY_KEY = 'd92549bb-fc5f-477a-9658-31fbfc852a65';

class VerificationError extends Error {}

function parseEnvironment(content) {
  const values = {};
  for (const line of content.split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue;
    const separator = line.indexOf('=');
    const key = separator > 0 ? line.slice(0, separator) : '';
    const value = separator > 0 ? line.slice(separator + 1) : '';
    if (!/^[A-Z][A-Z0-9_]*$/.test(key) || !value || Object.hasOwn(values, key)) {
      throw new VerificationError('IAM-002 private environment is malformed');
    }
    values[key] = value;
  }
  return values;
}

function decodeBase32(value) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const character of value.replace(/=+$/, '').toUpperCase()) {
    const index = alphabet.indexOf(character);
    if (index < 0) throw new VerificationError('IAM-002 private TOTP state is malformed');
    bits += index.toString(2).padStart(5, '0');
  }
  const bytes = [];
  for (let index = 0; index + 8 <= bits.length; index += 8) {
    bytes.push(Number.parseInt(bits.slice(index, index + 8), 2));
  }
  return Buffer.from(bytes);
}

function totpAt(secret, timestamp = Date.now(), digits = 6) {
  const counter = Math.floor(timestamp / 1000 / 30);
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(counter));
  const digest = crypto.createHmac('sha256', decodeBase32(secret)).update(message).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const binary = ((digest[offset] & 0x7f) << 24) |
    ((digest[offset + 1] & 0xff) << 16) |
    ((digest[offset + 2] & 0xff) << 8) |
    (digest[offset + 3] & 0xff);
  return (binary % (10 ** digits)).toString().padStart(digits, '0');
}

function actionUrlFromMessage(message) {
  const match = typeof message?.Text === 'string'
    ? message.Text.match(/https?:\/\/[^\s<>]+/)
    : null;
  if (!match) throw new VerificationError('Invitation email has no trusted local Keycloak action URL');
  let candidate;
  try {
    candidate = new URL(match[0].replaceAll('&amp;', '&'));
  } catch {
    throw new VerificationError('Invitation email has no trusted local Keycloak action URL');
  }
  if (candidate.origin !== KEYCLOAK_ORIGIN ||
      candidate.pathname !== '/realms/MERCHANT/login-actions/action-token' ||
      !candidate.searchParams.has('key')) {
    throw new VerificationError('Invitation email has no trusted local Keycloak action URL');
  }
  return candidate;
}

function assertApplicationCookieBoundaries(cookies) {
  const expected = new Map([
    ['PAYMENT_PLATFORM_SESSION', 'platform.localhost'],
    ['PAYMENT_MERCHANT_SESSION', 'merchant-e2e.localhost'],
    ['PAYMENT_AGENT_SESSION', 'agent.localhost'],
  ]);
  const applicationCookies = cookies.filter(({ name }) => expected.has(name));
  if (applicationCookies.length !== expected.size) {
    throw new VerificationError('IAM-002 application cookie boundary check failed');
  }
  for (const cookie of applicationCookies) {
    if (cookie.domain !== expected.get(cookie.name) || cookie.path !== '/' || cookie.secure !== false) {
      throw new VerificationError('IAM-002 application cookie boundary check failed');
    }
  }
}

function writePrivate(pathname, value) {
  fs.mkdirSync(path.dirname(pathname), { mode: 0o700, recursive: true });
  const temporary = `${pathname}.${process.pid}.tmp`;
  fs.writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  fs.renameSync(temporary, pathname);
  fs.chmodSync(pathname, 0o600);
}

function loadState() {
  if (fs.existsSync(STATE_PATH)) {
    const state = JSON.parse(fs.readFileSync(STATE_PATH, 'utf8'));
    if (state.schemaVersion !== 1 || typeof state.identities !== 'object' || !state.identities) {
      throw new VerificationError('IAM-002 private browser state is malformed');
    }
    return state;
  }
  const state = { identities: {}, schemaVersion: 1 };
  if (fs.existsSync(LEGACY_STATE_PATH)) {
    const legacy = JSON.parse(fs.readFileSync(LEGACY_STATE_PATH, 'utf8'));
    requireIdentitySecrets(legacy);
    state.identities.merchantAdministrator = {
      password: legacy.password,
      totpSecret: legacy.totpSecret,
    };
    writePrivate(STATE_PATH, state);
  }
  return state;
}

function requireIdentitySecrets(identity) {
  if (typeof identity?.password !== 'string' || identity.password.length < 14 ||
      typeof identity?.totpSecret !== 'string' || !/^[A-Z2-7]{20,}$/.test(identity.totpSecret)) {
    throw new VerificationError('IAM-002 private browser identity is malformed');
  }
  return identity;
}

function counter() {
  return Math.floor(Date.now() / 1000 / 30);
}

async function waitForFreshCounter(usedCounters, identityKey) {
  const previous = usedCounters.get(identityKey);
  while (previous !== undefined && counter() === previous) {
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  usedCounters.set(identityKey, counter());
}

async function clickAndLoad(page, locator) {
  await locator.click();
  await page.waitForLoadState('domcontentloaded');
}

async function enrollTotp(page, usedCounters, identityKey) {
  if (!await page.locator('#totp').count()) return null;
  if (await page.locator('#mode-manual').count()) {
    await Promise.all([
      page.waitForURL((url) => url.searchParams.get('mode') === 'manual'),
      page.locator('#mode-manual').click(),
    ]);
  }
  const secretElement = page.locator('#kc-totp-secret-key');
  if (!await secretElement.count()) {
    throw new VerificationError('Keycloak did not expose the manual TOTP enrollment key');
  }
  const secret = (await secretElement.textContent()).toUpperCase().replace(/[\s-]/g, '');
  if (!/^[A-Z2-7]{20,}$/.test(secret)) {
    throw new VerificationError('Keycloak returned an invalid TOTP enrollment key');
  }
  await waitForFreshCounter(usedCounters, identityKey);
  await page.locator('#totp').fill(totpAt(secret));
  if (await page.locator('#userLabel').count()) {
    await page.locator('#userLabel').fill('iam002-local-browser');
  }
  await clickAndLoad(page, page.locator('#saveTOTPBtn'));
  if (await page.locator('#totp').count()) {
    throw new VerificationError('Keycloak rejected local TOTP enrollment');
  }
  return secret;
}

async function completeRecoveryCodeSetup(page) {
  if (!await page.locator('#kcRecoveryCodesConfirmationCheck').count()) return false;
  await page.locator('#kcRecoveryCodesConfirmationCheck').check();
  await clickAndLoad(page, page.locator('#saveRecoveryAuthnCodesBtn'));
  return true;
}

async function waitForApplication(page, origin) {
  try {
    await page.waitForURL(
      (url) => url.origin === origin &&
        !['/api/auth/oidc/callback', '/auth/oidc/callback'].includes(url.pathname),
      { timeout: 30_000 },
    );
  } catch {
    const current = new URL(page.url());
    const inputIds = await page.locator('input').evaluateAll((elements) =>
      elements.map((element) => element.id || element.name).filter(Boolean));
    const feedback = (await page.locator(
      '.alert-error, .kc-feedback-text, #kc-page-title, .pf-v5-c-alert__title, h1',
    ).allTextContents()).join(' ').replace(/\s+/g, ' ').trim().slice(0, 240);
    throw new VerificationError(
      `Login remained at ${current.origin}${current.pathname} ` +
      `(query=${[...current.searchParams.keys()].sort().join(',') || 'none'}; ` +
      `inputs=${inputIds.sort().join(',') || 'none'}; feedback=${feedback || 'none'})`,
    );
  }
}

async function login(context, portal, identity, state, usedCounters) {
  requireIdentitySecrets(identity);
  const page = await context.newPage();
  await page.goto(`${portal.origin}/auth/login`, { waitUntil: 'networkidle' });
  const continueButton = page.getByText(/^(继续登录|Continue)$/, { exact: true });
  if (!await continueButton.count()) {
    throw new VerificationError(`${portal.name} did not render the OIDC login command`);
  }
  await continueButton.click();
  await page.waitForURL((url) => url.origin === KEYCLOAK_ORIGIN, { timeout: 15_000 });
  if (await page.locator('#username').count()) await page.locator('#username').fill(portal.username);
  if (!await page.locator('#password').count()) {
    throw new VerificationError(`${portal.name} did not require Realm authentication`);
  }
  await page.locator('#password').fill(identity.password);
  await clickAndLoad(page, page.locator('#kc-login'));
  if (await page.locator('#password').count()) {
    throw new VerificationError(`${portal.name} rejected the local test identity`);
  }
  if (await page.locator('#otp').count()) {
    await waitForFreshCounter(usedCounters, portal.identityKey);
    await page.locator('#otp').fill(totpAt(identity.totpSecret));
    await clickAndLoad(page, page.locator('#kc-login'));
    if (await page.locator('#otp').count()) {
      throw new VerificationError(`${portal.name} rejected the local MFA code`);
    }
  }
  const replacementSecret = await enrollTotp(page, usedCounters, portal.identityKey);
  if (replacementSecret) {
    identity.totpSecret = replacementSecret;
    state.identities[portal.identityKey] = identity;
    writePrivate(STATE_PATH, state);
  }
  await completeRecoveryCodeSetup(page);
  await waitForApplication(page, portal.origin);
  const result = await getApi(page, '/api/auth/codes');
  if (result.status !== 200) throw new VerificationError(`${portal.name} did not issue an application session`);
  return page;
}

async function stepUp(page, portal, identity, usedCounters) {
  const start = await postApi(page, '/api/auth/oidc/step-up/start');
  if (start.status !== 200 || typeof start.body?.data?.redirectUrl !== 'string') {
    throw new VerificationError(`${portal.name} did not start OIDC step-up`);
  }
  await page.goto(start.body.data.redirectUrl, { waitUntil: 'domcontentloaded' });
  if (await page.locator('#username').count()) await page.locator('#username').fill(portal.username);
  if (!await page.locator('#password').count()) {
    throw new VerificationError(`${portal.name} step-up did not require the password`);
  }
  await page.locator('#password').fill(identity.password);
  await clickAndLoad(page, page.locator('#kc-login'));
  if (!await page.locator('#otp').count()) {
    throw new VerificationError(`${portal.name} step-up did not require MFA`);
  }
  await waitForFreshCounter(usedCounters, portal.identityKey);
  await page.locator('#otp').fill(totpAt(identity.totpSecret));
  await clickAndLoad(page, page.locator('#kc-login'));
  if (await page.locator('#otp').count()) {
    throw new VerificationError(`${portal.name} rejected the step-up MFA code`);
  }
  await waitForApplication(page, portal.origin);
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
    const headers = { 'X-CSRF-Token': csrfBody.data.requestProof };
    if (requestBody !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(url, {
      body: requestBody === undefined ? undefined : JSON.stringify(requestBody),
      headers,
      method: 'POST',
    });
    let responseBody = null;
    try { responseBody = await response.json(); } catch {}
    return { body: responseBody, status: response.status };
  }, { requestBody: body, url: route });
}

async function assertForeignCookieRejected(context, origin) {
  const page = await context.newPage();
  const response = await page.goto(`${origin}/api/auth/codes`, { waitUntil: 'domcontentloaded' });
  await page.close();
  if (response?.status() !== 401) {
    throw new VerificationError('A foreign portal cookie was accepted across account domains');
  }
}

async function latestMail(email, timeout = 45_000, notBefore = 0) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const response = await fetch('http://127.0.0.1:18025/api/v1/messages');
    if (!response.ok) throw new VerificationError('Mailpit message API is unavailable');
    const list = await response.json();
    const messages = (list.messages || [])
      .filter((message) => message.To?.some((recipient) => recipient.Address === email))
      .filter((message) => Date.parse(message.Created) >= notBefore)
      .sort((left, right) => String(right.Created).localeCompare(String(left.Created)));
    if (messages.length) {
      const detail = await fetch(`http://127.0.0.1:18025/api/v1/message/${encodeURIComponent(messages[0].ID)}`);
      if (!detail.ok) throw new VerificationError('Mailpit message detail API is unavailable');
      return detail.json();
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new VerificationError('Keycloak did not send the local invitation email');
}

async function completeMfaRecoveryAction(browser, specification, identity, state,
                                         usedCounters, notBefore) {
  const message = await latestMail(specification.email, 45_000, notBefore);
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(actionUrlFromMessage(message).toString(), { waitUntil: 'domcontentloaded' });
    const proceed = page.getByText('Click here to proceed', { exact: false });
    if (await proceed.count()) await clickAndLoad(page, proceed);
    const replacementSecret = await enrollTotp(
      page, usedCounters, specification.identityKey,
    );
    if (!replacementSecret || await page.getByText('We are sorry', { exact: false }).count()) {
      throw new VerificationError('Keycloak rejected the MFA re-enrollment action');
    }
    identity.totpSecret = replacementSecret;
    state.identities[specification.identityKey] = identity;
    writePrivate(STATE_PATH, state);
  } finally {
    await context.close();
  }
}

async function activateInvitation(browser, specification, state, usedCounters) {
  const message = await latestMail(specification.email);
  const url = actionUrlFromMessage(message);
  const generatedCredential = [
    'Aa1!',
    crypto.randomBytes(24).toString('base64url'),
  ].join('');
  const identity = {
    password: generatedCredential,
  };
  const context = await browser.newContext();
  const page = await context.newPage();
  try {
    await page.goto(url.toString(), { waitUntil: 'domcontentloaded' });
    const proceed = page.getByText('Click here to proceed', { exact: false });
    if (await proceed.count()) await clickAndLoad(page, proceed);
    if (await page.locator('input[name=firstName]').count()) {
      await page.locator('input[name=firstName]').fill(specification.firstName);
      await page.locator('input[name=lastName]').fill(specification.lastName);
      await clickAndLoad(page, page.locator('input[type=submit], button[type=submit]'));
    }
    if (!await page.locator('#password-new').count()) {
      throw new VerificationError('Keycloak invitation did not require an initial password');
    }
    await page.locator('#password-new').fill(identity.password);
    await page.locator('#password-confirm').fill(identity.password);
    if (await page.locator('#logout-sessions').count() &&
        await page.locator('#logout-sessions').isChecked()) {
      await page.locator('#logout-sessions').uncheck();
    }
    await clickAndLoad(page, page.locator('#kc-submit'));
    identity.totpSecret = await enrollTotp(page, usedCounters, specification.identityKey);
    if (await page.getByText('We are sorry', { exact: false }).count()) {
      throw new VerificationError('Keycloak rejected the invitation required-action flow');
    }
    requireIdentitySecrets(identity);
    state.identities[specification.identityKey] = identity;
    writePrivate(STATE_PATH, state);
    return identity;
  } finally {
    await context.close();
  }
}

function postgresRow(sql) {
  return execFileSync('docker', [
    'compose', '--env-file', ENV_PATH, '-f', COMPOSE_PATH,
    'exec', '-T', 'postgres', 'psql', '-X', '-U', 'payment_dev', '-d', 'payment_platform',
    '-At', '-F', '|', '-c', sql,
  ], { cwd: REPOSITORY_ROOT, encoding: 'utf8' }).trim();
}

async function waitForRecovery(idempotencyKey, timeout = 90_000) {
  if (!/^[0-9a-f-]{36}$/.test(idempotencyKey)) throw new VerificationError('Invalid recovery key');
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const row = postgresRow(
      `SELECT status, mfa_credentials_revoked_at IS NOT NULL, recovery_codes_revoked_at IS NOT NULL, ` +
      `keycloak_sessions_revoked_at IS NOT NULL, application_sessions_revoked_at IS NOT NULL, ` +
      `coalesce(last_error_code, '') FROM iam_mfa_recovery WHERE idempotency_key='${idempotencyKey}'`,
    );
    if (row) {
      const [status, mfa, recoveryCodes, keycloakSessions, applicationSessions, error] = row.split('|');
      if (error) throw new VerificationError(`MFA recovery relay failed with ${error}`);
      if (status === 'COMPLETED' && [mfa, recoveryCodes, keycloakSessions, applicationSessions]
          .every((value) => value === 't')) return;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new VerificationError('MFA recovery did not complete all revocation steps');
}

async function keycloakAdminLogout(realm, username, clientId, clientSecret) {
  const tokenResponse = await fetch(`${KEYCLOAK_ORIGIN}/realms/${realm}/protocol/openid-connect/token`, {
    body: new URLSearchParams({ grant_type: 'client_credentials' }),
    headers: { authorization: `Basic ${Buffer.from(`${clientId}:${clientSecret}`).toString('base64')}` },
    method: 'POST',
  });
  if (!tokenResponse.ok) throw new VerificationError(`${realm} lifecycle client could not authenticate`);
  const token = (await tokenResponse.json()).access_token;
  const usersResponse = await fetch(
    `${KEYCLOAK_ORIGIN}/admin/realms/${realm}/users?username=${encodeURIComponent(username)}&exact=true`,
    { headers: { authorization: `Bearer ${token}` } },
  );
  const users = usersResponse.ok ? await usersResponse.json() : [];
  if (users.length !== 1) throw new VerificationError(`${realm} local identity is ambiguous`);
  const logoutResponse = await fetch(
    `${KEYCLOAK_ORIGIN}/admin/realms/${realm}/users/${encodeURIComponent(users[0].id)}/logout`,
    { headers: { authorization: `Bearer ${token}` }, method: 'POST' },
  );
  if (!logoutResponse.ok) throw new VerificationError(`${realm} back-channel logout request failed`);
}

async function waitForSessionRejection(page, timeout = 20_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if ((await getApi(page, '/api/auth/codes')).status === 401) return;
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
  throw new VerificationError('Revoked application session remained usable');
}

async function run() {
  if (!fs.existsSync(CHROME_PATH)) throw new VerificationError('Google Chrome is required for local browser verification');
  const env = parseEnvironment(fs.readFileSync(ENV_PATH, 'utf8'));
  const state = loadState();
  const usedCounters = new Map();
  const { chromium } = require(path.join(REPOSITORY_ROOT, 'frontend/admin/node_modules/playwright'));
  const browser = await chromium.launch({ executablePath: CHROME_PATH, headless: true });
  const mainContext = await browser.newContext();

  const platformIdentity = {
    password: env.PAYMENT_PLATFORM_LOCAL_ADMIN_PASSWORD,
    totpSecret: env.PAYMENT_PLATFORM_LOCAL_ADMIN_TOTP_SECRET,
  };
  const platform = {
    identityKey: 'platformAdministrator',
    name: 'platform',
    origin: 'http://platform.localhost:15999',
    username: 'admin@platform.localhost',
  };
  const merchant = {
    identityKey: 'merchantAdministrator',
    name: 'merchant',
    origin: 'http://merchant-e2e.localhost:16002',
    username: `invite-${MERCHANT_BOOTSTRAP_IDEMPOTENCY_KEY}`,
  };
  const member = {
    identityKey: 'merchantMember',
    name: 'merchant-member',
    origin: merchant.origin,
    username: `invite-${MERCHANT_MEMBER_IDEMPOTENCY_KEY}`,
  };
  const agentIdentity = {
    password: env.PAYMENT_AGENT_LOCAL_ADMIN_PASSWORD,
    totpSecret: env.PAYMENT_AGENT_LOCAL_ADMIN_TOTP_SECRET,
  };
  const agent = {
    identityKey: 'agentAdministrator',
    name: 'agent',
    origin: 'http://agent.localhost:16001',
    username: 'admin@agent.localhost',
  };

  let memberContext;
  try {
    const platformPage = await login(mainContext, platform, platformIdentity, state, usedCounters);
    await stepUp(platformPage, platform, platformIdentity, usedCounters);
    const bootstrap = await postApi(platformPage, '/api/identity/tenant-bootstraps', {
      entryHost: 'merchant-e2e.localhost',
      firstAdministrator: {
        displayName: 'Merchant E2E Administrator',
        email: 'merchant-e2e-admin@example.test',
      },
      idempotencyKey: MERCHANT_BOOTSTRAP_IDEMPOTENCY_KEY,
      tenantCode: 'merchant-e2e',
      tenantName: 'Merchant E2E',
      tenantType: 'DIRECT_MERCHANT',
    });
    if (bootstrap.status !== 200 || !['RESERVED', 'COMPLETED'].includes(bootstrap.body?.data?.status)) {
      throw new VerificationError(
        `Platform tenant bootstrap failed (${bootstrap.status}, ` +
        `${bootstrap.body?.error || 'NO_ERROR_CODE'}, ${bootstrap.body?.data?.status || 'NO_STATUS'})`,
      );
    }
    console.log('PASS platform login, step-up, and tenant bootstrap');

    if (!state.identities.merchantAdministrator) {
      await activateInvitation(browser, {
        email: 'merchant-e2e-admin@example.test',
        firstName: 'Merchant E2E',
        identityKey: merchant.identityKey,
        lastName: 'Administrator',
      }, state, usedCounters);
    }
    const merchantIdentity = requireIdentitySecrets(state.identities.merchantAdministrator);
    await assertForeignCookieRejected(mainContext, merchant.origin);
    const merchantPage = await login(mainContext, merchant, merchantIdentity, state, usedCounters);
    await stepUp(merchantPage, merchant, merchantIdentity, usedCounters);

    const noCsrf = await merchantPage.evaluate(async () => {
      const response = await fetch('/api/identity/invitations', {
        body: JSON.stringify({}),
        headers: { 'Content-Type': 'application/json' },
        method: 'POST',
      });
      return response.status;
    });
    if (noCsrf !== 403) throw new VerificationError('Cookie authentication accepted a mutation without CSRF proof');
    const roles = await getApi(merchantPage, '/api/identity/invitation-roles');
    const roleId = roles.body?.data?.items?.[0]?.roleId;
    if (roles.status !== 200 || typeof roleId !== 'string') {
      throw new VerificationError('Merchant invitation roles are unavailable');
    }
    const invitation = await postApi(merchantPage, '/api/identity/invitations', {
      displayName: 'Merchant E2E Operator',
      email: 'merchant-e2e-member@example.test',
      idempotencyKey: MERCHANT_MEMBER_IDEMPOTENCY_KEY,
      roleIds: [roleId],
    });
    if (invitation.status !== 200 || !['RESERVED', 'COMPLETED'].includes(invitation.body?.data?.status)) {
      throw new VerificationError(
        `Merchant member invitation failed (${invitation.status}, ` +
        `${invitation.body?.error || 'NO_ERROR_CODE'}, ${invitation.body?.data?.status || 'NO_STATUS'})`,
      );
    }
    if (!state.identities.merchantMember) {
      await activateInvitation(browser, {
        email: 'merchant-e2e-member@example.test',
        firstName: 'Merchant E2E',
        identityKey: member.identityKey,
        lastName: 'Operator',
      }, state, usedCounters);
    }
    console.log('PASS merchant invitation, activation, CSRF, and step-up');

    const memberIdentity = requireIdentitySecrets(state.identities.merchantMember);
    memberContext = await browser.newContext();
    const memberPage = await login(memberContext, member, memberIdentity, state, usedCounters);
    const members = await getApi(merchantPage, '/api/identity/members?page=1&pageSize=20');
    const target = members.body?.data?.items?.find((item) => item.displayName === 'Merchant E2E Operator');
    if (members.status !== 200 || !target || target.currentMembership) {
      throw new VerificationError('Invited merchant member is not visible to the administrator');
    }
    const recoveryKey = crypto.randomUUID();
    const recoveryRequestedAt = Date.now() - 1_000;
    const recovery = await postApi(merchantPage, '/api/identity/mfa-recoveries', {
      idempotencyKey: recoveryKey,
      targetMembershipId: target.membershipId,
    });
    if (recovery.status !== 200 || recovery.body?.data?.status !== 'RECOVERY_PENDING') {
      throw new VerificationError('Merchant MFA recovery request failed');
    }
    await waitForRecovery(recoveryKey);
    await waitForSessionRejection(memberPage);
    await memberContext.close();
    await completeMfaRecoveryAction(browser, {
      email: 'merchant-e2e-member@example.test',
      identityKey: member.identityKey,
    }, memberIdentity, state, usedCounters, recoveryRequestedAt);
    memberContext = await browser.newContext();
    await login(memberContext, member, memberIdentity, state, usedCounters);
    console.log('PASS MFA credential, recovery-code, Keycloak-session, and application-session revocation');

    await assertForeignCookieRejected(mainContext, agent.origin);
    let agentPage = await login(mainContext, agent, agentIdentity, state, usedCounters);
    await keycloakAdminLogout(
      'AGENT', agent.username, 'agent-identity-lifecycle',
      env.PAYMENT_AGENT_KEYCLOAK_ADMIN_CLIENT_SECRET,
    );
    await waitForSessionRejection(agentPage);
    await agentPage.close();
    agentPage = await login(mainContext, agent, agentIdentity, state, usedCounters);
    const agentMembers = await getApi(agentPage, '/api/identity/members?page=1&pageSize=20');
    if (agentMembers.status !== 200 || agentMembers.body?.data?.items?.length !== 1) {
      throw new VerificationError('Agent identity governance endpoint failed');
    }
    console.log('PASS agent login and Keycloak back-channel logout');

    assertApplicationCookieBoundaries(await mainContext.cookies());
    console.log('PASS three host-only application cookie boundaries');
    console.log('IAM-002 local browser verification passed');
  } finally {
    if (memberContext) await memberContext.close().catch(() => {});
    await mainContext.close().catch(() => {});
    await browser.close().catch(() => {});
  }
}

module.exports = {
  actionUrlFromMessage,
  assertApplicationCookieBoundaries,
  parseEnvironment,
  totpAt,
};

if (require.main === module) {
  run().catch((error) => {
    try {
      writePrivate(path.join(PRIVATE_ROOT, 'verify-error.json'), {
        message: error instanceof Error ? error.message : String(error),
        stack: error instanceof Error ? error.stack : null,
        timestamp: new Date().toISOString(),
      });
    } catch {}
    console.error(error instanceof VerificationError
      ? error.message
      : 'IAM-002 local browser verification failed unexpectedly; inspect .local/iam002/verify-error.json');
    process.exitCode = 1;
  });
}
