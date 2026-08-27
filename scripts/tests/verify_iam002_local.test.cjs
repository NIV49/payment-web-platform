const assert = require('node:assert/strict');
const test = require('node:test');

const {
  actionUrlFromMessage,
  assertApplicationCookieBoundaries,
  parseEnvironment,
  totpAt,
} = require('../dev/verify_iam002_local.cjs');
const {
  PORTALS,
  temporaryLocalPassword,
} = require('../dev/verify_iam002_local_password.cjs');

test('parses a strict private environment', () => {
  assert.deepEqual(parseEnvironment('A=value\nB=second=value\n'), {
    A: 'value',
    B: 'second=value',
  });
  assert.throws(() => parseEnvironment('A=one\nA=two\n'), /malformed/);
  assert.throws(() => parseEnvironment('lower=value\n'), /malformed/);
  assert.throws(() => parseEnvironment('A=\n'), /malformed/);
});

test('generates the RFC 6238 SHA-256 vector', () => {
  assert.equal(
    totpAt('GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA', 59_000, 8),
    '46119246',
  );
});

test('accepts only the local MERCHANT action-token URL', () => {
  const valid = [
    'Complete the action:',
    'http://127.0.0.1:18080/realms/MERCHANT/login-actions/action-token?key=private&amp;client_id=x',
  ].join('\n');
  assert.equal(
    actionUrlFromMessage({ Text: valid }).origin,
    'http://127.0.0.1:18080',
  );
  assert.throws(
    () => actionUrlFromMessage({ Text: 'https://attacker.example/action?key=x' }),
    /trusted local Keycloak action URL/,
  );
  assert.throws(
    () => actionUrlFromMessage({ Text: 'http://127.0.0.1:18080/realms/AGENT/login-actions/action-token?key=x' }),
    /trusted local Keycloak action URL/,
  );
});

test('requires three host-only application cookies', () => {
  const valid = [
    { domain: 'platform.localhost', name: 'PAYMENT_PLATFORM_SESSION', path: '/', secure: false },
    { domain: 'merchant-e2e.localhost', name: 'PAYMENT_MERCHANT_SESSION', path: '/', secure: false },
    { domain: 'agent.localhost', name: 'PAYMENT_AGENT_SESSION', path: '/', secure: false },
  ];
  assert.doesNotThrow(() => assertApplicationCookieBoundaries(valid));
  assert.throws(
    () => assertApplicationCookieBoundaries(valid.map((cookie, index) =>
      index === 1 ? { ...cookie, domain: '.localhost' } : cookie)),
    /cookie boundary/,
  );
  assert.throws(
    () => assertApplicationCookieBoundaries(valid.slice(1)),
    /cookie boundary/,
  );
});

test('keeps each local password portal on an independent identity boundary', () => {
  assert.equal(PORTALS.length, 3);
  assert.deepEqual(
    new Set(PORTALS.map((portal) => portal.domain)),
    new Set(['PLATFORM', 'MERCHANT', 'AGENT']),
  );
  assert.equal(new Set(PORTALS.map((portal) => portal.origin)).size, 3);
  assert.equal(new Set(PORTALS.map((portal) => portal.username)).size, 3);
  assert.equal(new Set(PORTALS.map((portal) => portal.cookie)).size, 3);
});

test('generates policy-compliant temporary local passwords', () => {
  const generated = new Set();
  for (let index = 0; index < 100; index += 1) {
    const password = temporaryLocalPassword();
    generated.add(password);
    assert.match(password, /^[A-Za-z0-9!@#$%^&*]{16,24}$/);
    assert.match(password, /[0-9]/);
    assert.match(password, /[A-Z]/);
    assert.match(password, /[a-z]/);
    assert.equal(password.match(/[!@#$%^&*]/g)?.length, 4);
  }
  assert.equal(generated.size, 100);
});
