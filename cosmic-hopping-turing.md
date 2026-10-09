# 通用智能体流式页面

## Context
APP 在机器人连接超时时停在初始化页。需要所有应用页面右下角可见的浮动入口，打开不依赖机器人连接的通用智能体流式页面。本次接入职业规划与简历诊断，后续复用公共页面。用户确认文本简历字段为 `resume_content`，JD 文本字段为简历诊断文档中的 `job_info`，密钥从本地配置读取。

## 实施方案

### 全页面浮窗
- `app/src/main/java/com/hr/digitalhuman/app/DigitalHumanApp.java` 注册 Activity 生命周期回调，可复用浮动入口组件向每个 Activity 内容根视图添加右下角“智能体”按钮，覆盖所有 Fragment 页面，不需要系统悬浮窗权限。
- 新增独立 `AgentStreamActivity`，在 `app/src/main/AndroidManifest.xml` 注册为非导出 Activity。机器人初始化成功或自动待机只影响底层 MainActivity，不覆盖独立页面。通用页面自身的浮窗不重复创建 Activity。
- `ui/MainActivity.java` 在智能体页可见期间屏蔽机器人语音交互及自动唤醒对页面的干扰，退出后恢复既有策略；不取消机器人底盘安全限制，不扩大为所有业务页的生命周期重构。

### 通用页面
- 公共页面：智能体选择、资料输入、资料来源、开始、停止、重试、状态与可滚动流式结果。
- 小型配置模型定义智能体 ID、名称、专属 Key、请求构造；本次仅两个配置，不做复杂插件系统或未知智能体动态表单。
- 职业规划：类型为“晋升路径/转型建议”，固定 query 为“分析简历，提出职业建议”；文本使用 `inputs.resume_content`，文件使用 `inputs.file_url`，附带 `inputs.type`。该协议未定义 JD 字段，JD 可保留为参考资料但明确不参与职业规划请求。
- 简历诊断：`inputs.job_info` 为 JD 文本，文本简历使用 `inputs.resume_content`，文件使用 `inputs.file_url`，有职位标题时按示例传 `job_title`；query 取 JD 前20个字符，无 JD 时取简历名称，但必填 JD 为空仍阻止调用并提示补充。
- 简历文本和文件来源二选一，不同时发送两个简历字段。默认显示明确标注为虚构的示例 JD/简历，无传参直接使用文本能力，不强制上传。
- 沿用项目深蓝卡片样式和 `ui/UiDecor.java`，横屏资料/结果分栏，窄屏上下排列；结果区为主要区域，保持按钮可访问与滚动阅读。

### 参数及现有简历形态
- 沿用 `ui/display/ResumePdfActivity.java` 静态 start + Intent extras 习惯，支持智能体 ID、JD 文本、职位标题、简历文本、cos_key，及现有简历中心 `recordId/resumeName/targetPosition/downloadToken`。
- 参数仅在缺失时回退示例，不用示例冒充真实历史简历；冲突来源按明确规则选择或提示。
- 已核实 `ResumeCenterFragment.java:869–916` 历史记录包含下载凭据，`:1497` 通过 `/robot/resume/download/{downloadToken}?format=pdf` 获取 PDF；`:1088–1092` 有采集预览文本。实施前继续核实返回数据是否还包含简历正文/公网 URL，以及项目 JD 实际来源，优先使用已有正文，不猜字段。
- 已生成可下载的历史简历增加“智能体分析”入口，带入真实记录；若只有下载凭据而没有正文/cos_key，按现有后端路径下载 PDF，再上传智能体文件接口得到 cos_key，不将 downloadToken 或公网 URL 冒充 cos_key。
- 文件模式支持系统文件选择器，无新增存储权限；遵守50MB限制，显示文件名和来源。文本模式直接传 resume_content；已上传 cos_key 跳过上传。公网 URL 只有协议明确支持时才直接传，否则转为真实文件上传，不猜语义。

### 通用网络层
- 新增独立 HttpURLConnection/Gson 客户端，不混用机器人 API 的用户凭证或 SN 请求头。
- 统一接口：`https://api.pincaimao.com/agents/v1/chat/chat-messages`，POST JSON，Bearer专属Key、Accept text/event-stream、response_mode=streaming。
- 上传：`https://api.pincaimao.com/agents/v1/files/upload`，multipart file，读取 cos_key。仅文件来源需要上传；传入文本不产生文件。
- SSE 按空行事件边界处理，支持多行 data，追加 message/agent_message 的 answer，message_end 标记结束，error 显示错误；不把 node_finished 的完整文本重复追加。未收到完成事件的断流提示中断而非成功。
- 后台执行网络，主线程节流更新；禁止重复开始。停止/退出断开连接并取消任务，使用请求标识防止旧结果覆盖新请求；不记录密钥、完整简历或认证头。
- 下载简历仅使用现有后端路径，避免任意 URL 参数携带认证信息。对外部文件边界做大小/失败校验，不假造成功或自动回退示例。

### 配置与接入
- `app/build.gradle` 从 local.properties 读取 `agents.career.api.key` 和 `agents.resume.diagnosis.api.key`，正确转义 BuildConfig 字符串，默认空值，缺少 Key 时界面提示配置。
- 不写真实密钥。本地 Key 会随 APK 打包，仅适合内部演示；正式分发建议后端代理。
- 必要新增通用 Activity、浮动入口组件、配置模型、网络客户端和布局资源；修改 DigitalHumanApp、MainActivity、ResumeCenterFragment、Manifest、app/build.gradle。

## 验证与交付
- 不运行构建，不提交/推送/合并 Git。
- 静态检查资源引用、Manifest、API参数、生命周期取消、资料来源与敏感日志。若无法进行非构建运行验证，明确说明。
- 人工安装检查：所有应用页右下角入口；连接超时仍可打开；职业规划/诊断各用对应Key；默认文本示例无需上传；真实历史简历不回退示例；文件上传/已有cos_key分支；停止/重试/返回无残留请求；缺少Key、断网与截断流明确报错；无机器人播报干扰。
- 完成后回复提供简要配置名、Intent参数、两类请求JSON及手动检查项，不另建对接文档。
