<script setup>
import { computed, ref, watch } from 'vue';
import { productImage, productDescription, safeProductUrl } from '../utils/productPresentation';
const props = defineProps({ product: { type: Object, required: true }, customerFacing: Boolean });
const imageFailed = ref(false);
const source = computed(() => safeProductUrl(props.product.source));
const picture = computed(() => productImage(props.product));
watch(picture, () => { imageFailed.value = false; });
</script>
<template>
  <article class="recommended-product">
    <header>为您精选 <span>巴黎欧莱雅</span></header>
    <a class="product-summary" :href="source || undefined" target="_blank" rel="noopener noreferrer">
      <div class="product-image"><img v-if="picture && !imageFailed" :src="picture" :alt="product.name" loading="lazy" referrerpolicy="no-referrer" @error="imageFailed = true"><span v-else>L’ORÉAL<br>PARIS</span></div>
      <div class="product-info"><h3>{{ product.name }}</h3><small>{{ product.category || '护肤美妆' }}</small><strong v-if="product.price > 0">¥<em>{{ product.price }}</em></strong><small v-else>价格待确认</small></div>
    </a>
    <div class="product-reasons"><b>商品介绍</b><p>{{ productDescription(product) || '暂无可核实的官网介绍，您可以进入商品页面了解详细信息。' }}</p></div>
    <a v-if="source" class="product-action" :href="source" target="_blank" rel="noopener noreferrer">商品介绍</a>
    <footer>价格及库存以商品页面为准</footer>
  </article>
</template>
<style scoped>
.recommended-product { width: 350px; max-width: 100%; box-sizing: border-box; padding: 18px; background: #fff; border: 1px solid #ece8e4; border-radius: 14px; color: #252525; text-align: left; }
header { display: flex; justify-content: space-between; align-items: center; font-weight: 650; font-size: 15px; margin-bottom: 16px; }
header span { font-size: 11px; color: #999; font-weight: 400; }
.product-summary { display: flex; gap: 14px; color: inherit; text-decoration: none; }
.product-image { width: 108px; height: 120px; flex-shrink: 0; display: grid; place-items: center; background: #faf9f7; border-radius: 8px; overflow: hidden; }
.product-image img { width: 100%; height: 100%; object-fit: contain; }
.product-image span { text-align: center; font-size: 14px; letter-spacing: 1px; color: #777; }
.product-info { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; }
h3 { font-size: 15px; line-height: 1.5; margin: 0 0 6px; overflow-wrap: anywhere; }
.product-info small { color: #999; font-size: 12px; }
strong { margin-top: auto; color: #f56623; font-size: 16px; padding-top: 8px; }
em { font-style: normal; font-size: 28px; }
.product-reasons { list-style: none; padding: 0; margin: 14px 0; font-size: 12px; line-height: 1.65; color: #81766e; }
.product-reasons li { margin: 5px 0; }
.product-reasons p { margin: 6px 0 0; line-height: 1.8; }
.product-action { display: block; padding: 11px; border-radius: 7px; background: #ff6b21; color: #fff; text-decoration: none; text-align: center; font-size: 14px; font-weight: 600; }
footer { color: #aaa; font-size: 10px; margin-top: 8px; }
</style>
