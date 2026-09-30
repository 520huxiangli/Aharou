# 免费供应商

想低成本体验 AI 编程？下面这些平台提供免费额度或活动 token，注册后即可在 Aharou 中接入使用。免费额度通常有时效，用完或过期后需要充值，具体以各平台规则为准。

## MiMo 开放平台（小米）

- **平台**：小米 MiMo 开放平台
- **注册地址**：[platform.xiaomimimo.com](https://platform.xiaomimimo.com?ref=66QSUD)（邀请码 `66QSUD`）
- **说明**：小米自研 MiMo 系列模型（MiMo V2.5 等），注册后可申请 API Key 接入。

::: tip 新人福利
通过上方邀请链接注册送 10 元体验金，享首单 9 折；体验金 40 天有效。
:::

## 日日新

- **平台**：日日新大模型开放平台（商汤 SenseNova）
- **注册地址**：[platform.sensenova.cn](https://platform.sensenova.cn/login)
- **说明**：商汤科技旗下的大模型平台，注册登录后可申请 API Key。

::: tip 公测免费
目前处于公测阶段，token plan 免费。
:::

## 基元律动（OpenSquilla）

- **平台**：基元律动 TokenRhythm
- **官网地址**：[tokenrhythm.studio](https://tokenrhythm.studio/)
- **说明**：多模型统一 API 接入平台，配套 OpenSquilla 智能路由，注册后可申请 API Key 接入。

::: tip 新人福利
需先完成实名认证，认证通过后可获得 18 元体验金。
:::

## 硅基流动（SiliconFlow）

- **平台**：硅基流动（SiliconFlow）开放平台
- **活动地址**：[https://cloud.siliconflow.cn/i/HNDIQWXd](https://cloud.siliconflow.cn/i/HNDIQWXd)
- **说明**：通过邀请链接注册并完成实名认证可获取免费额度，活动截止到年底，具体以平台公告为准。

::: tip 实名认证
新用户需先注册并完成实名认证，方可获得赠送额度。
:::

## Agnes AI

- **平台**：Agnes AI（新加坡 Sapiens AI 团队）
- **注册地址**：[platform.agnes-ai.com](https://platform.agnes-ai.com/)
- **说明**：全模态 AI 平台，文本、图像、视频三大模型 API 无限期免费开放，OpenAI 兼容接口，注册后即可申请 API Key 接入。

::: tip 全模态免费
文本、图像、视频模型 API 均免费开放，免费访问有基础速率限制与公平使用配额，具体以平台规则为准。
:::

## 在 Aharou 中接入

拿到平台的 API Key 和 Base URL 后，按 [AI 供应商与模型](/guide/providers) 的步骤配置即可：

1. 打开「设置 → AI 配置 → AI 供应商」，点右上角 + 新建。
2. 填入上游格式、API Key、Base URL（填到域名即可，如 `https://api.deepseek.com`；带 `/v1` 也可以，App 会自动去重）。
3. 切到「模型」标签页拉取模型，回到主页即可开始对话。

如果平台对上游格式、Base URL 有特殊要求（例如提供 OpenAI 兼容或 Anthropic 兼容端点），以平台文档为准。