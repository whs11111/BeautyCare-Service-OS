# BeautyCare Service OS

面向美妆电商的 AI 客服辅助系统，融合聊天、订单与工单信息，提供用户画像、个性化推荐、情绪分析及承诺跟进，通过实时响应与长期服务双闭环辅助人工客服。

本项目是本地演示原型，不是可直接上线的商业客服系统。订单与售后操作使用演示信息，不代表接通真实交易系统。本项目与巴黎欧莱雅不存在官方合作或背书关系。

## 主要功能

- 多客服独立登录、机器人接待、人工接管与会话分配。
- 服务助手按“接待重点、用户画像、服务记录”组织信息。
- 展示会话前情、用户轨迹、情绪变化、回复草稿与待处理事项。
- 一分钟测肤问卷，收集11题用户自述并同步画像。
- 根据需求及商品字段生成推荐理由与商品卡片。
- 保留服务承诺，识别时间并提醒跟进。
- 图片及文件附件，模型路由与历史消息恢复版本校验。

## 启动方法

准备 Node.js 22.12+（22系列）、npm、JDK 21、Maven 3.6.3+。Python 和本地 LoRA 权重不是运行主系统的必需条件。

在项目根目录打开 PowerShell：

```powershell
Set-Location server-v2/server
Copy-Item .env.example .env.local
```

编辑 `.env.local`，填入自己的 DeepSeek Key，再启动后端：

```powershell
mvn spring-boot:run
```

后端地址为 `http://127.0.0.1:8080`，默认仅本机访问。本发布版默认使用 DeepSeek 文本模型；如需千问，将 `AI_PROVIDER` 改为 `qwen` 并配置 `DASHSCOPE_API_KEY`。视觉分析需要单独配置千问视觉模型。本地 LoRA 服务需自行准备，不包含模型权重。

另开终端，从项目根目录启动前端：

```powershell
Set-Location web
npm ci
npm run dev
```

访问 `http://localhost:5175/login`。客服账号为 `agent1`、`agent2`、`agent3`，演示密码均为 `123456`。没有配置有效模型密钥时，普通页面和聊天可体验，AI 功能可能报错或进入已有降级逻辑。后端重启后需重新登录。

## 数据保存与安全

- 聊天、画像、问卷与任务等运行数据保存在用户主目录的 `.beautycare-service-os/`，不在本发布包内。
- 浏览器也会缓存状态。测试恢复数据时，不要让旧页面反复写回旧数据。
- 本发布版默认关闭模型调用训练日志，避免无意保存对话。
- 云端 AI 调用会向所配置的供应商发送相关上下文，图片任务还可能发送图片。
- 固定演示密码、部分未鉴权接口及原型级存储不具备生产安全能力，禁止直接暴露到公网。
- GitHub Pages 不能运行 Java 后端，上传源码不等于在线部署。

## 测试与构建

前端在 `web` 目录运行 `npm run build`。可执行的回归测试示例：

```powershell
node tests/history-revision.mjs
node tests/reception-display.mjs
node tests/service-identity.mjs
node tests/product-presentation.mjs
node tests/quiz-selection.mjs
```

后端在 `server-v2/server` 目录运行 `mvn -DskipTests package`。后端大部分场景测试是独立 `main` 类，应在 IDE 中单独运行，不应把 `mvn test` 解释为全部场景已通过。部分测试会创建数据，需设置独立的测试用户目录。

## 真实能力边界

统一工作流编排尚未全部接入前端。退款、补发、库存与工单完成情况需要真实业务接口或人工核实。提醒需要工作台打开，关闭提醒不等于业务完成。问卷不是医疗诊断，商品字段不能替代完整成分与库存核验。本发布版不宣称已有真实的准确率或成本降幅。
