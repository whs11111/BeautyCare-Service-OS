<script setup>
import { ElMessageBox } from 'element-plus';
const props = defineProps({ card: { type: Object, required: true } });
const showDetails = () => ElMessageBox.alert(props.card.details || '此内容仅用于演示，不可真实领取或下单。', `${props.card.title} · 演示`, { confirmButtonText: '知道了' }).catch(() => {});
</script>
<template>
  <article class="robot-promotion" :class="`promotion-${card.type}`">
    <div class="promotion-heading"><b>{{ card.title }}</b><span>演示</span></div>
    <template v-if="card.type === 'monthly'">
      <p class="card-intro">本月护肤好物，为您精选</p>
      <div class="product-preview">
        <div class="product-picture" aria-label="护肤品示意图"><svg viewBox="0 0 100 120" role="img" aria-label="修护霜"><ellipse cx="50" cy="105" rx="33" ry="5" fill="#ded8ca" /><rect x="22" y="48" width="56" height="54" rx="13" fill="#fdfcf8" stroke="#d5cfbf" /><rect x="20" y="38" width="60" height="20" rx="5" fill="#a3ad97" /><path d="M31 44h38" stroke="#c7cebc" stroke-width="2" /><text x="50" y="75" text-anchor="middle" font-size="8" fill="#666f59">BEAUTY CARE</text><text x="50" y="88" text-anchor="middle" font-size="6" fill="#939b87">BARRIER CREAM</text></svg></div>
        <div class="product-copy"><b>巴黎欧莱雅小蜜罐面霜4.0 滋润版</b><span>花蜜奢养 · 成分以包装为准</span><strong>¥<em>339</em></strong></div>
      </div>
      <p class="offer-line">{{ card.value }} · {{ card.subtitle }}</p>
    </template>
    <template v-else-if="card.type === 'coupon'">
      <div class="coupon-ticket"><strong class="promotion-value">{{ card.value }}</strong><div><b>{{ card.subtitle }}</b><span>您的专属护肤礼遇</span></div></div>
    </template>
    <template v-else><strong class="promotion-value">{{ card.value }}</strong><p>{{ card.subtitle }}</p></template>
    <button type="button" @click="showDetails">{{ card.action || '查看详情' }}</button>
    <small>虚构展示，不可领取、核销或下单</small>
  </article>
</template>
<style scoped>
.robot-promotion { width: 340px; max-width: 100%; padding: 18px; box-sizing: border-box; background: #fff; border: 1px solid #eee; border-radius: 10px; color: #252525; }
.promotion-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; font-size: 14px; }
.promotion-heading span { border: 1px solid #c9d9ef; border-radius: 4px; padding: 1px 5px; font-size: 10px; color: #6681a7; }
.promotion-value { display: block; margin: 18px 0 12px; font-size: 23px; line-height: 1.3; color: #252525; }
.robot-promotion p { margin: 0 0 15px; font-size: 12px; line-height: 1.5; }
.robot-promotion button { width: 100%; border: 0; background: #ff6900; color: white; border-radius: 7px; padding: 11px; cursor: pointer; font: inherit; font-size: 14px; font-weight: 600; }
.robot-promotion > small { display: block; font-size: 10px; color: #778aa3; margin-top: 9px; }
.coupon-ticket { display: flex; align-items: center; gap: 18px; background: #fff5eb; border: 1px dashed #ffc294; border-radius: 6px; padding: 12px; margin: 15px 0; }
.coupon-ticket .promotion-value { color: #f15a24; font-size: 38px; margin: 0; padding-right: 16px; border-right: 1px dashed #ffc294; }
.coupon-ticket span, .product-copy > span { display: block; font-size: 12px; color: #999; margin-top: 8px; }
.promotion-coupon button { background: #342b23; }
.card-intro { margin-top: 14px !important; font-size: 14px !important; }
.product-preview { display: flex; gap: 12px; margin-bottom: 12px; }
.product-picture { width: 100px; height: 112px; flex-shrink: 0; background: #f2f0e9; border-radius: 6px; }
.product-picture svg { width: 100%; height: 100%; }
.product-copy { min-width: 0; padding-top: 5px; }
.product-copy > b { font-size: 14px; line-height: 1.5; }
.product-copy > strong { display: block; color: #ff5c00; margin-top: 14px; font-size: 14px; }
.product-copy em { font-size: 25px; font-style: normal; }
.product-copy small { color: #aaa; font-weight: 400; font-size: 10px; margin-left: 8px; }
.offer-line { color: #de681e; background: #fff5eb; padding: 6px 8px; border-radius: 4px; }
</style>
