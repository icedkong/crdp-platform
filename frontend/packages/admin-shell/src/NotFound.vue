<template>
  <a-result status="404" title="页面不存在" sub-title="这个地址没有对应的菜单。">
    <template #extra>
      <router-link to="/dashboard">返回工作台</router-link>
    </template>
  </a-result>
</template>
