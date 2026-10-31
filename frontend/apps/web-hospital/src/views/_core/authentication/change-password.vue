<script lang="ts" setup>
import { computed, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { useUserStore } from '@vben/stores';

import { PASSWORD_HISTORY_COUNT, passwordRuleItems } from '@crdp/api';
import { Button, Card, Form, FormItem, InputPassword } from 'ant-design-vue';

import { changePasswordApi } from '#/api';
import { useAuthStore } from '#/store';

/**
 * E04-T05 / PLT-AUTH-006、010：首次登录（初始 / 重置 / 到期密码）强制改密页。
 * 按密码策略实时提示（pages.md §1「按密码策略实时提示规则是否满足」）；
 * 历史密码（最近 5 次）由后端比对，前端只提示规则。改密成功后继续完成登录。
 */
defineOptions({ name: 'ChangePassword' });

const authStore = useAuthStore();
const userStore = useUserStore();
const router = useRouter();

const formRef = ref();
const submitting = ref(false);
const form = reactive({
  confirmPassword: '',
  newPassword: '',
  oldPassword: '',
});

const ruleItems = computed(() =>
  passwordRuleItems(form.newPassword, usernameForPolicy()),
);

/** 账号从登录后的用户信息里取（改密页必然已登录，「不得包含账号」按当前账号判断）。 */
function usernameForPolicy() {
  return userStore.userInfo?.username ?? null;
}

const rules = {
  confirmPassword: [
    {
      message: '两次输入的新密码不一致',
      trigger: 'blur',
      validator: (_rule: unknown, value: string) =>
        value === form.newPassword
          ? Promise.resolve()
          : Promise.reject(new Error('两次输入的新密码不一致')),
    },
  ],
  newPassword: [
    {
      message: '密码长度至少 8 位',
      min: 8,
      trigger: 'blur',
    },
  ],
  oldPassword: [{ message: '请输入原密码', required: true, trigger: 'blur' }],
};

async function submit() {
  await formRef.value?.validate();
  if (form.newPassword !== form.confirmPassword) {
    return;
  }
  submitting.value = true;
  try {
    await changePasswordApi({
      newPassword: form.newPassword,
      oldPassword: form.oldPassword,
    });
    // 成功：当前会话已恢复完整权限，继续完成登录（拉权限码与菜单、进首页）
    await authStore.continueAfterPasswordChange();
  } finally {
    submitting.value = false;
  }
}

async function backToLogin() {
  await authStore.logout(false);
  await router.replace('/login');
}
</script>

<template>
  <Card class="w-full max-w-[400px]" :bordered="false">
    <div class="mb-2 text-center">
      <div class="text-xl font-semibold">修改密码</div>
      <div class="text-muted-foreground mt-1 text-sm">
        首次登录或密码已过期，需设置新密码后继续使用
      </div>
    </div>
    <Form
      ref="formRef"
      :label-col="{ span: 0 }"
      :model="form"
      :rules="rules"
      layout="vertical"
      @keyup.enter.prevent
    >
      <FormItem name="oldPassword">
        <InputPassword v-model:value="form.oldPassword" placeholder="原密码" />
      </FormItem>
      <FormItem name="newPassword">
        <InputPassword v-model:value="form.newPassword" placeholder="新密码" />
      </FormItem>
      <FormItem name="confirmPassword">
        <InputPassword
          v-model:value="form.confirmPassword"
          placeholder="确认新密码"
        />
      </FormItem>
      <!-- 按密码策略实时提示（PLT-AUTH-006） -->
      <ul class="text-muted-foreground mb-2 list-inside list-disc text-sm">
        <li
          v-for="item in ruleItems"
          :key="item.label"
          :class="item.ok ? 'text-[#1DC29E]' : ''"
        >
          {{ item.ok ? '✅' : '⭕' }} {{ item.label }}
        </li>
        <li>不能与最近 {{ PASSWORD_HISTORY_COUNT }} 次使用过的密码相同</li>
      </ul>
      <Button
        block
        :loading="submitting"
        size="large"
        type="primary"
        @click="submit"
      >
        确认修改并继续
      </Button>
      <Button block class="mt-3" type="link" @click="backToLogin">
        退出登录
      </Button>
    </Form>
  </Card>
</template>
