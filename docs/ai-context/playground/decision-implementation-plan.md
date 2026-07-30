# Playground Decisions Implementation Plan

## Identity

- Decision/base SHA: `60b31f74dd0f1506ab6f0e3449dcd176f93880da`
- Original source SHA: `3afbd5b2e120df4ecc49c99038650914985a0427`
- Branch: `codex/playground-decisions-3afbd5`
- Accepted decisions: `docs: record accepted playground decisions` at the
  decision/base SHA above
- Scope: existing product configuration and additive API response correction

## Governance Classification

This is not a legacy modernization Capability Slice. It has no source snapshot,
Rule Card, migration, or claim of formal Judge closure. The modernization
policy currently has no trusted reviewer keys or Judge checks, so this task may
produce only a local draft candidate and independent technical review. It must
not be reported as formally Judge closed.

## Acceptance Criteria

1. Product route generation uses an immutable application-owned `mixed` mode.
   Cached preferences, preference reset, or a user toggle cannot change that
   routing mode and the candidate does not persist a new access-mode value.
2. Mixed mode loads `/menu/all`; the backend remains authoritative for business
   routes, and locally retained source cannot admit a business route that the
   backend did not return.
3. The local side of mixed mode is a structural allowlist containing only the
   hidden `Profile` route. It cannot add `System`, `Dashboard`, `Demos`, or
   `VbenProject` routes that the backend did not return.
4. `Profile` reserves both route name `Profile` and canonical path `/profile`.
   A backend route colliding on either dimension fails route generation before
   merge, including a different-name/same-path collision.
5. Demo and Vben reference source remains in the repository but is not
   registered as a product route. Direct navigation reaches existing not-found
   handling.
6. `/user/info` satisfies the framework `UserInfo` shape with `userId`,
   `avatar`, `desc`, and `token`. The Web DTO supplies an empty description and
   the fixed non-secret cookie-session marker; Core and persistence do not gain
   a synthetic description field.
7. The frontend maps an explicit network DTO to `UserInfo`, rejects missing,
   null, or non-marker `token`, and never copies that field into the access
   token store. Unknown additive response fields are ignored.
8. Existing role assignment, `meta.activePath`, `authCode`, backend dynamic
   menu, and no-direct-user-menu-grant behavior remain unchanged.

## Ordered Work

1. Add failing route-contract tests for immutable mixed selection, the local
   allowlist, and same-name/same-path backend collision rejection.
2. Add failing frontend current-user mapping tests and a failing backend
   `/user/info` response assertion for `desc/token`.
3. Add the application-owned routing mode, pass only the structural Profile
   allowlist into mixed generation, and validate backend routes against the
   reserved local name/path before merge.
4. Separate the hidden `Profile` route from the retained Vben reference module.
5. Add the explicit current-user network DTO mapper and the additive Web-layer
   response fields without exposing a real credential.
6. Update Vben, frontend, backend, and formal API context from target decision
   to implemented current fact. Do not rewrite unrelated Playground reference
   material.
7. Run focused RED/GREEN checks, project gates, browser checks, and immutable
   diff scans. Commit an immutable local draft candidate only after they pass
   or record the exact unavailable gate.
8. Obtain an independent read-only review of the exact final SHA and fix every
   BLOCKER; handle each SHOULD_FIX or record evidence-based disposition.

## Explicitly Skipped

- Do not reimplement user role assignment, backend dynamic menus,
  `meta.activePath`, or user-to-role-only authorization; they already exist.
- Do not bind menu `authCode` to the Permission Catalog in this slice.
- Do not add user detail, registration, forgot-password, SMS, QR, or third-party
  login endpoints.
- Do not enable `/auth/refresh`. Cookie-only transport is accepted, but the
  credential model, rotation, replay handling, concurrency, TTL, revocation,
  logout linkage, and IdP compatibility remain unspecified. Extending
  `PAYMENT_SESSION` is not treated as a refresh token.
- Do not create reviewer keys, Judge checks, Rule Cards, or modernization
  closure artifacts for this non-modernization task.

## Security And Compatibility

- Backend authorization remains the enforcement boundary; frontend route
  visibility cannot widen API access.
- No real credential is returned to JavaScript, stored in local storage, sent
  through `Authorization`, or logged.
- The backend `/user/info` fields are additive and safe for the old frontend.
  The new frontend is therefore released only after the backend response is
  verified.
- No database schema, migration, money flow, or production deployment is in
  scope.

## Verification Checkpoints

- RED: focused tests fail on the exact claimed production seams before code.
- GREEN: focused frontend route/user mapping tests and backend contract test
  pass.
- Frontend: Node `24.16.0`; affected Vitest files; product typecheck; product
  build; production-safety tests.
- Backend: affected Admin API test, then `./mvnw -s maven-settings.xml clean verify`.
- Repository: `git diff --check`; documentation decision/link checks;
  `scripts/check_sensitive_artifacts.py` for the immutable base/candidate diff.
- Runtime: `/system/menu` remains backend-provided; `/profile` resolves;
  `/demos` and `/vben-admin` are not registered; a synthetic backend `/profile`
  collision fails closed.
- Modernization authoritative gate is not applicable to this non-modernization
  task. Empty trusted-reviewer/Judge registries remain a formal-closure limit,
  not a reason to fabricate artifacts.

## Release And Rollback

- Owner: deployment operator for Admin frontend/backend; no autonomous deploy
  is authorized by this task.
- Rollout: deploy backend first; verify old frontend login and `/user/info`;
  then canary the frontend and verify login success, user-info error rate, menu
  generation errors, route-not-found rate, `/system/menu`, and `/profile`.
- Stop: any real credential appears; login or `/user/info` failures increase;
  an omitted backend route appears; an authorized backend route disappears; or
  menu generation reports a reserved-name/path collision from real data.
- Rollback: roll back frontend first. The backend additive fields may remain for
  old clients. Because the candidate does not persist a new access-mode
  preference, returning to the base frontend restores its prior cached/reset
  behavior without a client cache migration. Roll back the backend second only
  after the old frontend is verified.
