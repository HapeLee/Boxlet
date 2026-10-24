# Android M3 / M3 Expressive 修改记录

对应 [修改前审查](android-m3-audit-2026-10-23.md)。文字遵循用户补充要求：自由正文选择主题 style；TopAppBar、按钮、导航项、对话框、输入框 label、ListItem 等槽位中的 Text 可以直接继承组件样式。

## 当前主题策略（两个项目同步）

Boxlet 与 D:/Project/Matter 均直接使用 MaterialExpressiveTheme 的默认 typography、shapes 和 motionScheme，不再维护 Type.kt 或应用级 Shapes 表。配色设置与定制玻璃组件继续保留。

顶栏随已动画化的玻璃显示进度，在 headlineMediumEmphasized 与 headlineSmallEmphasized 两档主题 TextStyle 之间平滑插值；淡出时反向恢复。Matter 的公共搜索框使用同样规则。没有手写字号或字重，复用原有显隐动画，不叠加第二套延迟。

验证：Boxlet assembleDebug 成功，设计规则检查 38 个文件、0 违规；Matter compileDebugKotlin 和 testDebugUnitTest 成功，168 tests、0 failures/errors/skips。Boxlet 实机检查了无胶囊及有胶囊两个状态。

下方为前几轮修改记录；涉及主题包装或固定标题档位的描述以本节为准。

## 定制顶栏和底栏保留策略

按用户补充要求，顶栏和底栏属于有意设计的视觉例外：已恢复原始玻璃配方、透明度、高光/阴影、紧凑尺寸和文字样式。底栏保留 56dp 高度、每项 60dp 基准宽度、两端 18dp 留白、拖拽阻尼及文字/图标交叉渐变；顶栏保留 44dp 按钮、64dp 栏高及 headlineSmallEmphasized 标题（对齐 Matter 的 24sp 视觉层级）。这里不再强制替换成标准 M3 导航外观，也不因窗口变宽改成 NavigationRail。

此前本轮曾采用的标准 NavigationBarItem、80dp 底栏、96% 顶底栏玻璃底色、48dp 可见顶栏按钮和宽窗口侧栏均已撤回。其他页面与备用组件的规范化修改保留。

## 已实施

- **排版**：已删除 Type.kt，直接采用 MaterialExpressiveTheme 提供的默认排版；删除自定义字号、字重、行高和字距。EmptyMessage 的 faceTextSize 改为 faceStyle，默认使用 headlineLarge。补齐自由布局正文的 bodyMedium/bodyLarge；定制顶栏标题使用 headlineSmallEmphasized（Matter 自定义 headlineMedium 为 24sp；本项目使用默认 24sp 档位以对齐视觉），其他组件槽位保留样式继承。未修改组件提供的字重或字号。
- **网格**：修正审查列出的页面、公共组件及备用组件的非网格 padding、Spacer、spacedBy 和图标尺寸。描边、插画、阴影、折射、动画位移及系统 Insets 不机械取整。
- **接收头卡**：设备名使用 titleMediumEmphasized，状态使用 bodyMedium，端口使用 labelSmall；PIN 使用 labelSmallEmphasized。名称可两行、状态自然换行，元信息和操作支持换行。
- **导航**：恢复原始自绘玻璃底栏，逐项补充 Role.Tab、selected、读屏点击动作及 Enter/空格键激活；保留原始触摸手势，不叠加另一套点击处理。交叉渐变文字清除重复语义，名称由父导航项统一提供。内容底部避让恢复与原始 56dp 高度一致。
- **设置开关**：父行通过 toggleable 提供唯一切换入口，Switch(onCheckedChange=null) 仅显示状态。无点击行为的 SettingItem 不再产生空 clickable。
- **交互空间和反馈**：定制顶栏玻璃按钮恢复 44dp 视觉尺寸和原始缩放反馈，依赖 Compose clickable 的触摸区域扩展；没有为统一控件而改变外观。其他可点击标签和 EmptyMessage 表情仍预留至少 48dp；备用 TabRow 保留已补齐的选中语义与交互空间。
- **对比度**：浅色 tertiary 调整为 #00677D，与白色 onTertiary 对比度由 2.76:1 提升至 **6.49:1**。设置说明和未收藏图标去掉额外降低对比度的 alpha。定制顶底栏恢复原始透明玻璃配方，备用 TabRow 保留较实的容器表面。
- **大字体**：接收头卡允许换行，自动接收选项根据实际文字宽度在空间不足时纵向排列。字体设置造成 Activity 重建时对齐完整分页，避免停留动画中间。顶底栏尺寸遵循原始定制设计。
- **宽窗口**：保留定制悬浮底栏，不再切换成 NavigationRail。页面列表仍居中并限制最大宽度 840dp。备用组件的尺寸读取改用实际窗口信息。
- **规则检查**：新增 `python scripts/check-android-design.py`。检查直接文字指标覆盖，以及 literal padding/Spacer/图标等尺寸。支持继承样式、运行时测量和动画，明确不代替实机布局检查。

## 恢复定制样式后的验证

- `:androidApp:assembleDebug` 再次通过；设计规则检查仍通过。
- 已在已连接设备运行，并查看恢复后的顶栏和紧凑玻璃底栏：[最终定制栏截图](../artifacts/m3-custom-bars-restored.png)。
- 以下正常/大字体/宽屏截图属于恢复前的验证过程；最终顶底栏视觉以上述截图为准。正文布局与开关修正保持不变。

## 前一阶段验证

- `:androidApp:assembleDebug` 成功，APK 已在已连接设备上安装运行。
- 设计规则检查：**39 个 Kotlin 文件，0 项违规**。该脚本只检查明确的文字指标覆盖及字面量尺寸，无法证明所有运行时计算值或所有 UI 都合规。
- 实机检查手机底栏发送/接收/设置切页，查看导航选中态与可交互语义。
- 实机语义树确认可见设置开关的父行具有 CHECKABLE / CLICKABLE / FOCUSABLE，子 Switch 不再单独处理切换。
- 实机检查接收页正常和 200% 字体；确认头卡与自动接收标签完整显示。正常截图：[接收页](../artifacts/m3-receive.png)；大字体：[200% 接收页](../artifacts/m3-receive-font-200-final.png)。
- 宽窗口截图为此前采用 NavigationRail 的中间版本，用户要求保留原设计后已撤回该导航变更；该截图不代表最终效果。
- 检查结束后已恢复设备 font_scale=1.0 和原始 1272×2772 屏幕尺寸，无尺寸 override。

## 仍需注意

`:androidApp:lintDebug` 已执行，结果为 **3 errors / 18 warnings / 3 hints**。3 个错误均位于本轮未修改的 `service/TransferForegroundService.kt`：

- 331 行：notify 的通知权限检查（MissingPermission）。
- 247、268 行：setRequestPromotedOngoing 需要 API 36.1，目前仅按 API 36 保护（NewApi）。

本轮没有隐藏 Lint 错误、添加 baseline 或修改通知服务。完整结果位于 `androidApp/build/reports/lint-results-debug.html`。

尚未做完整 TalkBack 语音交互、所有动态壁纸/浅色背景对比度矩阵，以及实际传输请求/失败/完成状态的大字体截图测试。此次设备截图以深色就绪状态为主；不能将源码修正和局部验证当成所有运行状态的无障碍认证。

## 设置项编辑交互

Boxlet 的设备名称、端口、设备型号、接收 PIN、多播组与接口黑白名单改为摘要列表项，点击打开 M3 AlertDialog 编辑。草稿使用 rememberSaveable，取消或关闭不提交，保存和 IME Done 共用校验。端口保留 1024–65535 范围；名称及多播组不能为空；PIN 与接收页一致，支持 4–8 位数字或留空关闭，列表不显示明文。目录选择、恢复默认目录和重启服务统一为设置条目。

Matter 设置页原本已使用点击条目和独立编辑面板，本次无需修改。Boxlet assembleDebug 和设计规则检查通过；实机确认列表无输入框、对话框自动聚焦、无效端口不提交、取消不改值、重新打开恢复原值及有效确认关闭。未测试修改网络参数后的真实服务重启或目录授权流程；复用原有更新与系统目录选择逻辑。

## 发送与接收尺寸动画（简化版本）

按用户要求，发送清单和接收头卡只在内容 Column 上使用普通 animateContentSize()，修饰符置于 padding 前。移除新增的 AnimatedContent/AnimatedVisibility 内容切换以及 TransferMotion 辅助文件，保留原来的插画与进度动画。

MatterCardPadded 的内部 Column 统一使用 animateContentSize()，覆盖附近设备卡片、本机信息卡、浏览器分享卡和传输详情卡。附近设备本身由满屏 LazyColumn 承载，不给固定大小的列表重复添加尺寸动画；空态及设备条目使用 animateItem() 处理增删和位移。接收历史及全部已选文件列表也保留普通 animateItem()。

compileDebugKotlin 与设计规则检查通过（39 个文件，0 违规）。按用户要求，本轮未操作设备，实际动画体验由用户检查。

### 文件清单区域与外层列表的联动修正

后续反馈卡片仍跳变后，发送面板不再给整张内容 Column 做尺寸动画，而是将 animateContentSize() 放到始终存在的文件清单区域（包含可选分隔线和“查看全部”）。外层 Column 依据该区域的动画高度放置下方入口；避免只动画卡片边界、内部入口直接跳到目标位置。标题行和加载区域各自处理自身尺寸变化，不与整卡动画叠加。

发送面板增加 modifier 参数，外层 LazyColumn 中的发送卡片、附近设备标题、浏览器分享卡和本机信息卡均接入 animateItem()，使用稳定 key。卡片内部的内联文件仍是最多 8 项的普通 Column，没有新增嵌套滚动列表。assembleDebug 和设计检查通过，本次未操作设备，尚待用户实测动画体验。
