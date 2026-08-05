import type { AuthApi } from '@payment/backoffice-runtime/api';

import type { Recordable, UserInfo } from '@vben/types';

import { ref } from 'vue';
import { useRouter } from 'vue-router';

import { LOGIN_PATH } from '@vben/constants';
import { preferences } from '@vben/preferences';
import { resetAllStores, useAccessStore, useUserStore } from '@vben/stores';

import {
  getAccessCodesApi,
  getUserInfoApi,
  LOGIN_CREDENTIAL_FIELD,
  loginApi,
  logoutApi,
  oidcHandoffApi,
} from '@payment/backoffice-runtime/api';
import {
  OIDC_START_PATH,
  resolveRealmLogoutUrl,
} from '@payment/backoffice-runtime/api/core/oidc-navigation';
import { COOKIE_SESSION_MARKER } from '@payment/backoffice-runtime/api/session';
import { $t } from '@payment/backoffice-runtime/locales';
import {
  isProductSessionGenerationCurrent,
  resetProductRoutes,
  startProductSessionGeneration,
} from '@payment/backoffice-runtime/router/route-lifecycle';
import { notification } from 'antdv-next';
import { defineStore } from 'pinia';

export const useAuthStore = defineStore('auth', () => {
  const accessStore = useAccessStore();
  const userStore = useUserStore();
  const router = useRouter();

  const loginLoading = ref(false);
  type AuthLoginResult = { userInfo: null | UserInfo };
  let pendingLogin: null | Promise<AuthLoginResult> = null;
  let pendingLoginResponse: null | Promise<void> = null;
  let pendingLogout: null | Promise<void> = null;

  function clearAuthenticatedRouteState() {
    const sessionGeneration = startProductSessionGeneration();
    resetProductRoutes(router);
    accessStore.setAccessToken(null);
    accessStore.setAccessCodes([]);
    accessStore.setAccessMenus([]);
    accessStore.setAccessRoutes([]);
    accessStore.setIsAccessChecked(false);
    userStore.setUserInfo(null);
    return sessionGeneration;
  }

  /**
   * Establish frontend state after either local login or OIDC handoff.
   */
  async function establishAuthenticatedSession(
    authenticationAttempt: Promise<AuthApi.LoginResult>,
    sessionGeneration: number,
    onSuccess?: () => Promise<void> | void,
  ): Promise<AuthLoginResult> {
    let userInfo: null | UserInfo = null;
    try {
      loginLoading.value = true;
      const loginResponse = authenticationAttempt.then(
        () => undefined,
        () => undefined,
      );
      pendingLoginResponse = loginResponse;
      let loginResult;
      try {
        loginResult = await authenticationAttempt;
      } finally {
        if (pendingLoginResponse === loginResponse) {
          pendingLoginResponse = null;
        }
      }
      const sessionMarker = loginResult.accessToken;

      // 真实会话只存在于 HttpOnly Cookie，store 仅保存非敏感登录态标记。
      if (sessionMarker !== COOKIE_SESSION_MARKER) {
        throw new Error('Invalid cookie-session login response');
      }
      if (!isProductSessionGenerationCurrent(sessionGeneration)) {
        return { userInfo };
      }
      accessStore.setAccessToken(COOKIE_SESSION_MARKER);

      const [fetchUserInfoResult, accessCodes] = await Promise.all([
        getUserInfoApi(),
        getAccessCodesApi(),
      ]);

      if (!isProductSessionGenerationCurrent(sessionGeneration)) {
        return { userInfo };
      }
      userInfo = fetchUserInfoResult;

      userStore.setUserInfo(userInfo);
      accessStore.setAccessCodes(accessCodes);

      if (accessStore.loginExpired) {
        accessStore.setLoginExpired(false);
      } else {
        onSuccess
          ? await onSuccess?.()
          : await router.push(
              userInfo.homePath || preferences.app.defaultHomePath,
            );
      }

      if (userInfo?.realName) {
        notification.success({
          description: `${$t('authentication.loginSuccessDesc')}:${userInfo?.realName}`,
          duration: 3,
          title: $t('authentication.loginSuccess'),
        });
      }
    } finally {
      if (isProductSessionGenerationCurrent(sessionGeneration)) {
        loginLoading.value = false;
      }
    }

    return {
      userInfo,
    };
  }

  function performAuthLogin(
    params: Recordable<any>,
    onSuccess?: () => Promise<void> | void,
  ): Promise<AuthLoginResult> {
    const sessionGeneration = clearAuthenticatedRouteState();
    return establishAuthenticatedSession(
      loginApi({
        [LOGIN_CREDENTIAL_FIELD]: String(params.password ?? ''),
        username: String(params.username ?? ''),
      }),
      sessionGeneration,
      onSuccess,
    );
  }

  function performOidcHandoff(
    handoff: string,
    onSuccess?: () => Promise<void> | void,
  ): Promise<AuthLoginResult> {
    const sessionGeneration = clearAuthenticatedRouteState();
    return establishAuthenticatedSession(
      oidcHandoffApi(handoff),
      sessionGeneration,
      onSuccess,
    );
  }

  function trackLogin(login: Promise<AuthLoginResult>) {
    pendingLogin = login;
    const clearPendingLogin = () => {
      if (pendingLogin === login) pendingLogin = null;
    };
    login.then(clearPendingLogin, clearPendingLogin);
    return login;
  }

  function authLogin(
    params: Recordable<any>,
    onSuccess?: () => Promise<void> | void,
  ): Promise<AuthLoginResult> {
    if (pendingLogout) {
      return pendingLogout.then(
        () => authLogin(params, onSuccess),
        () => authLogin(params, onSuccess),
      );
    }
    if (pendingLogin) {
      return pendingLogin;
    }
    return trackLogin(performAuthLogin(params, onSuccess));
  }

  function redeemOidcHandoff(
    handoff: string,
    onSuccess?: () => Promise<void> | void,
  ): Promise<AuthLoginResult> {
    if (pendingLogout) {
      return pendingLogout.then(
        () => redeemOidcHandoff(handoff, onSuccess),
        () => redeemOidcHandoff(handoff, onSuccess),
      );
    }
    if (pendingLogin) return pendingLogin;
    return trackLogin(performOidcHandoff(handoff, onSuccess));
  }

  function startOidcLogin() {
    if (loginLoading.value || pendingLogin || pendingLogout) return;
    clearAuthenticatedRouteState();
    loginLoading.value = true;
    window.location.assign(OIDC_START_PATH);
  }

  const isLoggingOut = ref(false);

  async function performLogout(
    redirect: boolean,
    sessionGeneration: number,
    loginResponse: null | Promise<void>,
  ) {
    await loginResponse;
    let realmLogoutUrl: string | undefined;
    try {
      const result = await logoutApi();
      if (result?.logoutUrl) {
        realmLogoutUrl = resolveRealmLogoutUrl(
          result.logoutUrl,
          window.location.origin,
        );
      }
    } catch {
      // 不做任何处理
    } finally {
      if (isProductSessionGenerationCurrent(sessionGeneration)) {
        resetAllStores();
        accessStore.setLoginExpired(false);
      }
    }

    if (!isProductSessionGenerationCurrent(sessionGeneration)) {
      return;
    }

    if (realmLogoutUrl) {
      window.location.assign(realmLogoutUrl);
      return;
    }

    // 回登录页带上当前路由地址
    await router.replace({
      path: LOGIN_PATH,
      query: redirect
        ? {
            redirect: router.currentRoute.value.fullPath,
          }
        : {},
    });
  }

  function logout(redirect: boolean = true): Promise<void> {
    if (pendingLogout) return pendingLogout;
    isLoggingOut.value = true;
    const sessionGeneration = clearAuthenticatedRouteState();
    const logout = performLogout(
      redirect,
      sessionGeneration,
      pendingLoginResponse,
    );
    pendingLogin = null;
    pendingLogout = logout;
    const clearPendingLogout = () => {
      if (pendingLogout === logout) {
        pendingLogout = null;
      }
      isLoggingOut.value = false;
    };
    logout.then(clearPendingLogout, clearPendingLogout);
    return logout;
  }

  async function fetchUserInfo(sessionGeneration: number) {
    const userInfo = await getUserInfoApi();
    if (isProductSessionGenerationCurrent(sessionGeneration)) {
      userStore.setUserInfo(userInfo);
    }
    return userInfo;
  }

  function $reset() {
    loginLoading.value = false;
    isLoggingOut.value = false;
  }

  return {
    $reset,
    authLogin,
    fetchUserInfo,
    loginLoading,
    logout,
    redeemOidcHandoff,
    startOidcLogin,
  };
});
