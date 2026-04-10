<template>
  <div v-if="payBody" v-html="payBody"></div>
  <div v-else>支付表单为空，请返回订单页重试。</div>
</template>

<script setup>
import { useRoute } from 'vue-router'

const { query } = useRoute()
const cachedPayBody = window.sessionStorage.getItem('ALIPAY_PAY_BODY')
const payBody =
  cachedPayBody || (query?.body ? decodeURIComponent(query.body) : '')
if (cachedPayBody) {
  window.sessionStorage.removeItem('ALIPAY_PAY_BODY')
}

setTimeout(() => {
  if (payBody) {
    document.forms[0]?.submit()
  }
}, 100)
// const state = reactive({
//   body: ''
// })

// onMounted(() => {
//   //   const profile = Vue.extend({
//   //     template
//   //   })
//   //   state.body = query
// })
</script>

<style lang="scss" scoped></style>
