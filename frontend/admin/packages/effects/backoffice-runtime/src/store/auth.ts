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
} from '@payment/backoffice-runtime/api';
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
   * 异步处理登录操作
   * Asynchronously handle the login process
   * @param params 登录表单数据
   */
  async function performAuthLogin(
    params: Recordable<any>,
    onSuccess?: () => Promise<void> | void,
  ): Promise<AuthLoginResult> {
    // 异步处理用户登录操作并获取 accessToken
    let userInfo: null | UserInfo = null;
    const sessionGeneration = clearAuthenticatedRouteState();
    try {
      loginLoading.value = true;
      const loginRequest = loginApi({
        [LOGIN_CREDENTIAL_FIELD]: String(params.password ?? ''),
        username: String(params.username ?? ''),
      });
      const loginResponse = loginRequest.then(
        () => undefined,
        () => undefined,
      );
      pendingLoginResponse = loginResponse;
      let loginResult;
      try {
        loginResult = await loginRequest;
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
    const login = performAuthLogin(params, onSuccess);
    pendingLogin = login;
    const clearPendingLogin = () => {
      if (pendingLogin === login) {
        pendingLogin = null;
      }
    };
    login.then(clearPendingLogin, clearPendingLogin);
    return login;
  }

  const isLoggingOut = ref(false);

  async function performLogout(
    redirect: boolean,
    sessionGeneration: number,
    loginResponse: null | Promise<void>,
  ) {
    await loginResponse;
    try {
      await logoutApi();
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
  };
});
