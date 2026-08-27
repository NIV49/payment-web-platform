import { describe, expect, it } from 'vitest';

import enUS from './langs/en-US/merchant.json';
import zhCN from './langs/zh-CN/merchant.json';

describe('merchant locale contracts', () => {
  it('keeps the active business labels exact without replay-only DIRECT', () => {
    expect(zhCN.permission).toEqual({
      create: '新增商户',
      disable: '禁用',
      edit: '编辑',
      enable: '启用',
      review: '审核',
      terminate: '终止',
    });
    expect(enUS.permission).toEqual({
      create: 'Create Merchant',
      disable: 'Disable',
      edit: 'Edit',
      enable: 'Enable',
      review: 'Review',
      terminate: 'Terminate',
    });
    expect(zhCN.review).toBe('审核');
    expect(zhCN.disable).toBe('禁用');
    expect(zhCN.enable).toBe('启用');
    expect(zhCN.terminate).toBe('终止');
    expect(zhCN.edit).toBe('编辑');
    expect(zhCN.create).toBe('新增');
    expect(enUS.review).toBe('Review');
    expect(enUS.disable).toBe('Disable');
    expect(enUS.enable).toBe('Enable');
    expect(enUS.terminate).toBe('Terminate');
    expect(enUS.edit).toBe('Edit');
    expect(enUS.create).toBe('Create');
    expect(zhCN.fields.displayName).toBe('商户名称');
    expect(zhCN.fields.legalName).toBe('主体名称');
    expect(zhCN.fields.registrationCountry).toBe('主体注册国家/地区');
    expect(zhCN.onboarding.fields.displayName).toBe('商户名称');
    expect(zhCN.onboarding.fields.legalName).toBe('主体名称');
    expect(zhCN.onboarding.fields.registrationCountry).toBe(
      '主体注册国家/地区',
    );
    expect(zhCN.onboarding.fields.marketCodes).toBe('经营市场');
    expect(zhCN.reasons.PLATFORM_APPLICATION_SUBMITTED).toBe(
      '平台代建申请已提交',
    );
    expect(zhCN.fields.legalPersonName).toBe('法人名称');
    expect(zhCN.merchantTypes).toEqual({
      COMMISSION: '分佣商户',
      INDIRECT: '间连商户',
      PLATFORM: '平台商户',
      SALES: '销售商户',
    });
    expect(zhCN.authenticationTypes).toEqual({
      CLIQUE: '集团',
      ENTERPRISE: '企业',
      INDIVIDUAL: '个人',
      INDIVIDUAL_HOUSEHOLD: '个体户',
      NON_PROFIT_ORGANIZATIONS: '非盈利组织',
    });
    expect(enUS.merchantTypes).toEqual({
      COMMISSION: 'Commission Merchant',
      INDIRECT: 'Indirect Merchant',
      PLATFORM: 'Platform Merchant',
      SALES: 'Sales Merchant',
    });
    expect(enUS.authenticationTypes).toEqual({
      CLIQUE: 'Group',
      ENTERPRISE: 'Enterprise',
      INDIVIDUAL: 'Individual',
      INDIVIDUAL_HOUSEHOLD: 'Sole Proprietor',
      NON_PROFIT_ORGANIZATIONS: 'Non-profit Organization',
    });
    expect(enUS.fields.displayName).toBe('Merchant Name');
    expect(enUS.fields.legalName).toBe('Legal Entity Name');
    expect(enUS.fields.registrationCountry).toBe(
      'Entity Registration Country/Region',
    );
    expect(enUS.onboarding.fields.displayName).toBe('Merchant Name');
    expect(enUS.onboarding.fields.legalName).toBe('Legal Entity Name');
    expect(enUS.onboarding.fields.registrationCountry).toBe(
      'Entity Registration Country/Region',
    );
    expect(enUS.onboarding.fields.marketCodes).toBe('Operating Markets');
    expect(enUS.reasons.PLATFORM_APPLICATION_SUBMITTED).toBe(
      'Platform-created application submitted',
    );
  });
});
