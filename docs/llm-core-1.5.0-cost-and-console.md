# LLM Core 1.5.0：模型费用与 Console

## 管理员入口

在 `/llm console` → Providers 点击标准供应商，Setup 中设置「输入倍率」「输出倍率」并保存。
一个供应商名称对应一个端点/模型；同厂商多个模型使用不同名称，即可分别定价并参与路由。
倍率由服务器管理员设置，默认均为 1，不自动获取厂商价格，也不代表真实货币。
例如输入 10 Token、输出 5 Token，输入倍率 2、输出倍率 4，消耗为 40 单位；原始用量仍为 15 Token。
倍率可设为 0，最大 1,000,000；分量分别向上取整，溢出饱和处理。

标准供应商在有效的 `config/llmcore/providers.json` 或服务器覆盖文件中保存：

```json
"billing": {
  "inputMultiplier": 0.5,
  "outputMultiplier": 2.5
}
```

未配置 billing 时默认 1；省略 outputMultiplier 时沿用 inputMultiplier。显式非法倍率会拒绝 reload，
保留上一份有效运行时。Setup 优先更新已有的服务器覆盖条目，避免保存到被覆盖的全局文件后不生效。
RAW 供应商在 `serverconfig/llmcore/providers_raw.json` 的对应条目中使用相同 billing 对象，
其请求也按加权消耗结算；RAW 模板仍通过文件维护，Console 展示倍率。

## 路由顺序

Routing 编辑默认路由或具体用途后，点击「优先低消耗」可按输入/输出 Token 数相等时的名义费用
稳定排序草稿。竞速阶段按成员费用之和比较；保留竞速分组、重试次数和总超时。同价阶段维持原顺序。
该排序依据单次尝试的倍率，不预测实际输入/输出比例或重试概率。排序结果仍可手动编辑。
空路由使用继承的候选项，点击排序会生成明确的草稿；点击保存才提交服务端。

采用管理员在客户端 Console 调整服务端路由的方案，保存后对全服生效。
普通玩家没有供应商、倍率或路由写入权限；本版没有新增普通玩家个人模型偏好。

## 预算、所有权与迁移

倍率、配置 reload 与路由仍归 llmcore-mod；CreatureChat 仅使用共享运行时 API。
个人额度现在以消耗单位计算：已结算费用 + 在途费用不得超过额度。
`/llm budget` 和 Console Budget 同时展示原始 Token 与加权费用。
旧的 `limitTokens` 字段/命令数值保留，个人额度的单位变为消耗单位；倍率默认 1 时行为一致。
因果请求链的 `LlmBillingContext.maxTokens` / `LlmCallBudget.maxTokens` 仍以原始 Token 为单位。

每次 provider attempt 发起时捕获费率，实际 usage 返回后按捕获费率结算；
缺失 usage、已发出的竞速败者或不确定用量的取消，沿用此次输入/输出预留成本，并保留 estimatedTokens 标记。
原始在途 Token 与在途费用分别统计，正常完成、取消、超时回收继续沿现有单一结算路径处理。

存档的 `llmcore/personal-budget.json` 升级到 schema 3。读取 schema 1/2 时，先创建
`personal-budget.json.schema-<旧版本>.<UUID>.bak` 原文备份，再一次性原子替换新账本；历史费用按 1 倍迁移。
旧额度、请求数和 Token 用量保留。schema 3 缺少 costUnits 会拒绝读取，防止损坏账本静默少计。
本版保留已发布公共构造器，现有 CreatureChat/LLMjs 消费者可链接；Console 网络协议同步升为 9，
多人环境客户端和服务器须安装同版 llmcore。

## 日志阅读

HTTP JSON 字符串中的 `\n` 是编码后的换行，供应商解析 JSON 后模型接收的是实际换行。
Console 详情与复制现在按 JSON 字符串解码一次，把真实换行和制表符展开用于人工阅读；
文本里字面上的反斜杠不再被二次解释。原始请求/响应记录保持原样，供诊断使用。
展开后的复制文本是人工阅读视图，包含字符串内部换行，不用于直接重放 HTTP JSON。
需要原始诊断记录时，可在日志中选中条目后按 Ctrl/Cmd+Shift+C，复制未经正文展开的原始请求/响应。

## 本次验证范围

定向测试覆盖真实 HTTP 与流式 usage、缺失 usage、竞速取消、原始链路上限、加权个人额度、
旧账本备份迁移、非法倍率拒绝 reload、配置保存/服务器覆盖和日志换行。
Forge 构建及运行时 source/jar 边界检查用于发布验收；游戏内布局和模型实际响应仍需实机体验。
