> 历史基线（截至早期版本），不代表当前状态。最新Android为1.71.0/code71；本轮最终要求与iOS已定位差异见 [iOS同步准备-2026-10-22.md](iOS同步准备-2026-10-22.md)。下文旧版三类分析、2000条上限、含VERIFIED等口径已被后续用户指示覆盖。

# Android → iOS 功能对齐清单 · 2026-10-21

这是需求与Android实现索引，**不是iOS缺失清单，也不是Android设备验收单**。新对话先比对iOS源码，再按用户选定项目实施。Android相对路径前缀为android/app/src/main/java/com/personal/tpm/。

## 卡片与外观

| 功能方向 | 最终要求/当前实现 | Android定位 |
| --- | --- | --- |
| 九类卡片配置 | 维修、ERP、维修待办、ERP待办、其他待办、资产、库存、保养、精密度，类型独立保存 | ui/CardPreferences.kt |
| 入口 | 我的→外观→卡片自定义，统一风格图标 | ui/CardSettingsScreen.kt、ui/AppRoot.kt |
| 字段可见性 | 缩略/展开/隐藏，可调整顺序、位置、显示字段名；缩略默认少标签，去掉占空间的“资产编码”等字样 | ui/CardPreferences.kt、ui/OrderCards.kt |
| 预览编辑 | 各类型自己的样例，真实卡片渲染，长按拖动、点击设置、同行选择、本地自动保存/恢复默认 | ui/CardPreviewModels.kt、ui/CardLayoutEditor.kt |
| 最新布局要求 | 含标题最多四行；下方六格；并排用“ · ”；详情按钮共享格位，能回右下；虚拟横线、高亮、吸附容差 | ui/CardPreferences.kt、ui/CardLayoutEditor.kt、ui/OrderCards.kt；未人工验收，优先按需求实现 |
| 骨架跟随配置 | 调整字段和卡片尺寸后，灰色骨架高度/布局也应对应 | ui/OrderCards.kt；四行版本需视觉验收 |
| 主题色 | 哔哩哔哩粉RGB255,102,153/#FF6699；去棕、蓝灰、普通蓝、青柠、深橙；保留胖次蓝，不混同普通蓝 | ui/theme/ThemeConfig.kt |
| 普通字段颜色 | 表单值、选择值、数量不全染主题强调色；恢复常规文字色 | ui/FormParts.kt、ui/ReferenceSheet.kt、ui/RepairFormScreen.kt、ui/ErpFormScreen.kt |
| 底栏 | 选中背景加深，保持可辨认 | ui/MainShell.kt、ui/theme/ThemeConfig.kt |

## 列表、加载与滚动

| 功能方向 | 最终要求/当前实现 | Android定位 |
| --- | --- | --- |
| 返回主页 | 自动刷新数量，避免重复请求/错厂区晚响应覆盖 | ui/HomeTab.kt、data/Session.kt |
| 大列表提醒 | 主页所有工单、已完结入口点击先提示会加载大量工单、等待较长，再确认进入 | ui/HomeTab.kt |
| 触底加载 | 真正触底自动取后续卡片，文字“加载更多内容”，不写“上划加载更多” | ui/AutoLoadMore.kt、ui/OrderListScreen.kt、ui/OrderListViewModel.kt |
| 动态省略号 | 加载点与文字同字号/颜色/字重，不另用过大的点 | ui/LoadingText.kt |
| 加载动画 | 增加多种SpinKit风格动画，保留MIT署名，不强依赖大型动画引擎 | ui/SpinKitStrip.kt、app/src/main/assets/licenses/SpinKit.txt |
| 出现与回收 | 透明度/高度同步过渡，动画消失后不留空白占位 | ui/Motion.kt、ui/OrderCards.kt、ui/Chrome.kt |
| 边缘渐变 | 可滚动页面/选择弹层顶底、完成/保存/提交固定栏与内容衔接一致 | ui/ScrollEdges.kt、ui/Chrome.kt |
| 选择器重叠 | 弹性滚动内容裁切，近期/全部树列表不越界覆盖上部文字，取消离场残影 | ui/Elastic.kt、ui/ReferenceSheet.kt |
| 高刷检查 | Android主窗口和弹层请求高刷；不代表强制帧率，不要移植成iOS固定帧率限制 | ui/WindowRefreshRate.kt、ui/Chrome.kt、MainActivity.kt |

## ERP、维修与批量业务

| 功能方向 | 最终要求/当前实现 | Android定位 |
| --- | --- | --- |
| 物料取消选择 | 已选项再点一次取消，去掉“已选择”阻断弹窗 | ui/ReferenceSheet.kt、ui/FormParts.kt、ui/BatchUi.kt |
| 提示复制按钮 | 提示/错误弹窗去掉复制按钮；不等同删除详情所有系统文本选择 | ui/Components.kt |
| ERP表单对齐 | 关联单号、数量框、内外圆角和边距统一；“出库物料”标题旁去多余星号，业务校验保留 | ui/ErpFormScreen.kt、ui/FormParts.kt |
| 工作说明分组 | 完成任务时工作说明与ERP出库分开，同级于图片等区域 | ui/RepairFormScreen.kt |
| 领料显示料号 | 完成工单已领料列表显示物料料号，兼容两种物料嵌套结构，批量/缺料提示也有料号 | ui/RepairFormScreen.kt、data/BatchMaterials.kt、ui/BatchUi.kt |
| 退单人异步 | 报修详情退单类工单自动读流程，取真实退单执行人；先显示动画加载中，失败可重试，不把受理人/候选人当退单人 | data/ReturnHistory.kt、ui/FaultDetailScreen.kt |
| 提交选审批人 | 提交任务前按真实流程分支取得下一节点和审批人，保留原字段，不猜测状态 | data/Workflow.kt、data/WorkflowSubmission.kt、ui/TaskScreen.kt |
| 批量前置校验 | 每单在领料/保存前校验权限、操作分支、审批人、班组、任务状态 | data/Batch.kt、data/BatchApi.kt、data/BatchDecisions.kt |
| 物料严格匹配 | 服务端出库行按物料ID一一匹配，缺失/重复/额外行不默认数量1，不继续错误提交 | data/BatchMaterials.kt、data/Batch.kt |
| 范围与选单冻结 | 账号/厂区切换丢弃旧任务；固定执行快照、类型+ID身份，避免维修/ERP同ID混淆 | data/Session.kt、data/Batch.kt、ui/BatchUi.kt |
| 部分失败 | 保存成功但流程未确认等阶段化提示，提示刷新核对；不自动重复领料、不伪称回滚 | data/Batch.kt、ui/BatchUi.kt |

原业务契约先读根目录流程核对.md；不要为验证这些功能执行真实业务写入。

## 我的、安全与开发者

| 功能方向 | 最终要求/当前实现 | Android定位 |
| --- | --- | --- |
| 开发者入口 | 连续点“我的”页面标题10次进入隐藏入口，密码1331；不是额外常驻普通入口 | ui/AppRoot.kt、ui/DevScreen.kt |
| 管理看板开关 | 开发者选项中开关，打开后工作台出现管理看板分类/人员与工单分析；仅控制入口，不授予后台权限 | ui/WorkspaceTab.kt、ui/CardSettingsScreen.kt、ui/DevScreen.kt |
| 保存账号保护 | 开启后在使用已记住账号前验证指纹/面容/系统锁屏密码；取消/失败不得继续；无设备安全锁不假成功 | ui/BiometricGate.kt、ui/BiometricGateExtras.kt、data/AccountVault.kt、MainActivity.kt |
| 保护范围说明 | 这是记住账号的凭据使用保护，不是每次进入App都锁屏；iOS用LocalAuthentication/Keychain对应能力 | 对照ios/TPM/Accounts.swift |
| 权限精简 | Android已去未使用网络状态权限，无电话/通讯录/短信/定位/相机/录音/存储敏感权限；安装权限、生物验证按需保留 | android/app/src/main/AndroidManifest.xml；iOS按自身功能审计 |

## 管理分析（最新方案，未服务端验收）

页面拆成概览/人员查询/处理统计；说明和错误按需展开，不首屏铺满。概览六个分页请求limit=1取服务端总数；人员按姓名+类型+状态服务端过滤，每页20条，点击才读取；个人近7/30/90天统计按三类×三种已处理状态取总数，取消全量导出再分析。

维修字段repairUser.realName、保养maintainUser.realName、精密度user.realName；同名可能合并。只读当前权限/当前厂区，不把维修受理人当执行人。专用analysis接口只发现声明，未证实DTO，不调用。iOS也应先核对服务器兼容性，不硬套未经验证的新字段。

Android源码data/ManagementAnalytics.kt、ui/ManagementScreen.kt；详细口径见android/管理看板数据口径-2026-10-21.md。

## 更新与发布：只迁移合适的平台能力

Android已取消bsdiff/bspatch增量，只用完整APK。启动检测，“我的”最下方应用更新；检查时显示最新版更新日志，客户端已支持notes后，即使本地旧版本也能读远端新版日志。下载进度、Range续传、三次重试、SHA256/大小/包名/版本/签名校验，再调用系统安装界面。同包名同签名覆盖，不卸载，数据保留仍需设备验收。无腾讯云密钥存客户端。

COS根地址：https://wataa-1259218212.cos.ap-guangzhou.myqcloud.com/ ，version.json为检查入口。tools/publish_android.py验证正式签名APK、生成清单与日志，可用发布机环境变量凭据上传，先数据后清单；使用说明android/自动更新发布说明.md。android/releases/1.58.0-notes.txt早于最近几轮新增修改，不是当前全部改动的最终发布日志。

**iOS不能直接照搬APK安装器、REQUEST_INSTALL_PACKAGES、Android签名或高刷窗口参数。** 如需iOS更新提示/日志，先确定发行方式；TestFlight/App Store与用户个人签名IPA各有约束，不承诺下载IPA即可自安装。两个平台版本号、清单与产物应分别管理。

## iOS查找起点（需比对，未确认缺失）

| 领域 | 当前iOS候选源码 |
| --- | --- |
| 卡片、骨架、排版 | ios/TPM/WorkOrderCard.swift、CardList.swift、DashboardCards.swift |
| 列表/主页/待办 | LiveWorkflows.swift、TodoInbox.swift、Refresh.swift |
| 批量 | BatchSelection.swift、BatchProcessing.swift |
| ERP/任务 | OriginalForms.swift、OriginalTasks.swift、WorkflowSubmission.swift |
| 流程/退单 | ReturnNotes.swift、WorkflowHistory.swift、WorkflowDisplay.swift |
| 外观/通用组件 | TPMApp.swift、InterfaceComponents.swift、Dialogs.swift |
| 账号/权限范围 | Accounts.swift、LiveAPI.swift、FactorySwitch.swift |
| 只读功能单池 | ReadOnlyPools.swift |

## 建议对齐顺序

先明确用户本次范围；优先处理低耦合的分组、料号、颜色、提示与加载文字，再核对列表/流程/批量业务；卡片编辑器按最终模型单独设计并设备验收，管理分析先验证只读筛选。全量移植不是本次默认授权。
