import type { Arrayable, MaybeElementRef } from '@vueuse/core';

import type { Ref } from 'vue';

import { computed, effectScope, ref, unref, watch } from 'vue';

import { tryOnScopeDispose, useElementHover } from '@vueuse/core';

export interface HoverDelayOptions {
  enterDelay?: (() => number) | number;
  leaveDelay?: (() => number) | number;
}

const DEFAULT_ENTER_DELAY = 0;
const DEFAULT_LEAVE_DELAY = 500;

export function useHoverToggle(
  refElement: Arrayable<MaybeElementRef> | Ref<HTMLElement[] | null>,
  delay: (() => number) | HoverDelayOptions | number = DEFAULT_LEAVE_DELAY,
) {
  const normalizedOptions: HoverDelayOptions =
    typeof delay === 'number' || typeof delay === 'function'
      ? { enterDelay: DEFAULT_ENTER_DELAY, leaveDelay: delay }
      : {
          enterDelay: DEFAULT_ENTER_DELAY,
          leaveDelay: DEFAULT_LEAVE_DELAY,
          ...delay,
        };

  const value = ref(false);
  const enterTimer = ref<ReturnType<typeof setTimeout>>();
  const leaveTimer = ref<ReturnType<typeof setTimeout>>();
  const hoverScopes = ref<ReturnType<typeof effectScope>[]>([]);

  const refs = computed(() => {
    const raw = unref(refElement);
    if (raw === null) return [];
    return Array.isArray(raw) ? raw : [raw];
  });
  const resolvedElements = computed(() =>
    refs.value.map((elementReference) => {
      if (!elementReference) return null;
      const rawElement = unref(elementReference);
      if (rawElement instanceof Element) return rawElement;
      const componentElement = rawElement?.$el;
      return componentElement instanceof Element ? componentElement : null;
    }),
  );
  const isHovers = ref<Array<Ref<boolean>>>([]);

  function updateHovers() {
    hoverScopes.value.forEach((scope) => scope.stop());
    hoverScopes.value = [];

    isHovers.value = resolvedElements.value.map((element) => {
      if (!element) return ref(false);
      const scope = effectScope();
      const hovered =
        scope.run(() => useElementHover(ref(element))) ?? ref(false);
      hoverScopes.value.push(scope);
      return hovered;
    });
  }

  updateHovers();
  const stopElementsWatcher = watch(resolvedElements, updateHovers, {
    flush: 'post',
  });
  const isOutsideAll = computed(() =>
    isHovers.value.every((item) => !item.value),
  );

  function clearTimers() {
    if (enterTimer.value) clearTimeout(enterTimer.value);
    if (leaveTimer.value) clearTimeout(leaveTimer.value);
    enterTimer.value = undefined;
    leaveTimer.value = undefined;
  }

  function resolveDelay(
    input: (() => number) | number | undefined,
    fallback: number,
  ) {
    if (input === undefined) return fallback;
    return typeof input === 'function' ? input() : input;
  }

  function setValueWithDelay(nextValue: boolean) {
    clearTimers();
    const timeout = resolveDelay(
      nextValue ? normalizedOptions.enterDelay : normalizedOptions.leaveDelay,
      nextValue ? DEFAULT_ENTER_DELAY : DEFAULT_LEAVE_DELAY,
    );
    if (timeout <= 0) {
      value.value = nextValue;
      return;
    }
    const timer = setTimeout(() => {
      value.value = nextValue;
      if (nextValue) enterTimer.value = undefined;
      else leaveTimer.value = undefined;
    }, timeout);
    if (nextValue) enterTimer.value = timer;
    else leaveTimer.value = timer;
  }

  const hoverWatcher = watch(
    isOutsideAll,
    (outside) => setValueWithDelay(!outside),
    { immediate: true },
  );

  tryOnScopeDispose(() => {
    clearTimers();
    stopElementsWatcher();
    hoverScopes.value.forEach((scope) => scope.stop());
  });

  return [
    value,
    {
      disable: () => hoverWatcher.pause(),
      enable: () => hoverWatcher.resume(),
    },
  ] as const;
}
