<script setup lang="ts">
import type { VbenFormSchema } from '#/adapter/form';

import { computed } from 'vue';

import { ProfilePasswordSetting, z } from '@vben/common-ui';

import { message } from 'ant-design-vue';

import { changePasswordApi } from '#/api';

/**
 * E06-T11 / PLT-ORG-011：个人中心「修改密码」（接口 E04-T05 /auth/password，两端同构）。
 * 成功后本会话继续可用，该账号其他会话全部失效。
 */
defineOptions({ name: 'ProfilePasswordSetting' });

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      fieldName: 'oldPassword',
      label: '旧密码',
      component: 'VbenInputPassword',
      componentProps: {
        placeholder: '请输入旧密码',
      },
    },
    {
      fieldName: 'newPassword',
      label: '新密码',
      component: 'VbenInputPassword',
      componentProps: {
        passwordStrength: true,
        placeholder: '8–64 位，含大小写 / 数字 / 特殊字符中至少 3 类',
      },
    },
    {
      fieldName: 'confirmPassword',
      label: '确认密码',
      component: 'VbenInputPassword',
      componentProps: {
        passwordStrength: true,
        placeholder: '请再次输入新密码',
      },
      dependencies: {
        rules(values) {
          const { newPassword } = values;
          return z
            .string({ required_error: '请再次输入新密码' })
            .min(1, { message: '请再次输入新密码' })
            .refine((value) => value === newPassword, {
              message: '两次输入的密码不一致',
            });
        },
        triggerFields: ['newPassword'],
      },
    },
  ];
});

async function handleSubmit(values: Record<string, any>) {
  await changePasswordApi({
    newPassword: values.confirmPassword,
    oldPassword: values.oldPassword,
  });
  message.success('密码修改成功，其他登录会话已全部下线');
}
</script>
<template>
  <ProfilePasswordSetting
    class="w-2/5"
    :form-schema="formSchema"
    @submit="handleSubmit"
  />
</template>
