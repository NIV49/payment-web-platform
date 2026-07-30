import type { DescriptionsItemType } from '@vben/common-ui';
import type { BasicUserInfo } from '@vben/types';

interface ProfileLabels {
  name: string;
  roles: string;
  userId: string;
  username: string;
}

export function createProfileDescriptionItems(
  userInfo: BasicUserInfo | null,
  labels: ProfileLabels,
): DescriptionsItemType[] {
  return [
    {
      content: userInfo?.realName || '-',
      key: 'realName',
      label: labels.name,
    },
    {
      content: userInfo?.username || '-',
      key: 'username',
      label: labels.username,
    },
    {
      content: userInfo?.userId || '-',
      key: 'userId',
      label: labels.userId,
    },
    {
      content: userInfo?.roles?.join(', ') || '-',
      key: 'roles',
      label: labels.roles,
    },
  ];
}
