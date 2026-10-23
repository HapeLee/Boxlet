# Android M3 / M3 Expressive 静态审查

> 以下为修改前的审查快照。已按审查完成本轮界面修正，逐项处理结果、验证及剩余限制见 [实施记录](android-m3-changes-2026-10-23.md)。组件槽位中正确继承的 Text 样式按用户补充要求保留。

日期：2026-10-23。范围：androidApp/src/main（含主题、页面、自定义组件、资源），并检查共享模块的职责和 Android 侧调用。没有修改业务代码，没有运行设备、TalkBack 或大字体截图测试；以下区分确定问题与待实机验证事项。

## 审查口径

- 项目硬约束：字号、字重只通过主题 style 选择，包括 Emphasized；不允许页面指定 fontSize/fontWeight，也不通过重新定义一套字号绕过要求。
- 项目硬约束：非零 padding、布局间距、图标尺寸使用 2.dp 或 4 的正整数倍。0 表示无间距，可以保留；系统 WindowInsets、响应式测量和动画中间值不机械取整。
- 该数值约束不自动扩展到描边、圆角、阴影、折射和插画路径。M3 允许定制主题及组件，不能把所有定制都直接定性为违反 M3。
- 优先级：P1 为主要交互可访问性问题；P2 为项目硬约束或组件一致性问题；P3 为待验证/备用组件问题。

## 确定发现

### 1. P1：主底栏没有逐项导航交互语义

`androidApp/src/main/kotlin/dev/boxlet/android/ui/glass/LegadoLiquidFloatingBottomBar.kt:198` 使用 pointerInput 处理整个底栏的点按和拖动；`:333` 开始的每个 Column 没有 selectable/clickable、Role.Tab、selected 或可访问性点击动作。图标 contentDescription 只提供名称，不能替代动作。TalkBack 无法按标准导航项激活“发送 / 接收 / 设置”，键盘也缺少逐项焦点和激活能力。

建议：使用 NavigationBarItem，或保留玻璃外观，为每项增加 selectable(selected, role = Role.Tab, onClick)，为容器增加 selectableGroup；共享同一个选中回调，处理顶层手势和子项点击的冲突。交叉渐变的两份文本还应只保留一份语义，避免重复朗读。选中样式应同时提供明确的非颜色视觉提示。

### 2. P2：主题覆盖了全部 15 个基础文字样式

`ui/Type.kt:13` 起，BoxletTypography 手工创建 TextStyle，设置 fontWeight/fontSize/lineHeight；`ui/Theme.kt` 将其传入 MaterialExpressiveTheme。

例如 displayLarge = 40sp/48sp + Bold、headlineSmall = 20sp/26sp + SemiBold、bodyLarge = 15sp/24sp、labelSmall = 11sp/14sp。页面即使选择 MaterialTheme.typography，也仍然得到这套自定义字号字重。

建议：按本项目要求使用 M3E 默认排版，使用 headlineSmall、titleMedium、bodyMedium、labelSmallEmphasized 等语义样式选择层级和强调。现有主题重写本身是 M3 支持的能力，但不符合本次要求的默认 M3E 样式收敛目标。顶栏 `ImmersiveTopBar.kt:315` 的 style.copy 只改阴影，不是字号/字重违规；不要误删。

### 3. P2：多处布局间距和图标尺寸违反项目网格

以下是手工复核后的违规位置；行号基于本次源文件。建议值按语义选择，不能只把全部数字四舍五入。

| 文件（均在 ui/ 下） | 行号与当前值 | 建议 |
| --- | --- | --- |
| Components.kt | 78：vertical padding 6；115：iconSize 30；249：Spacer 6；259：Icon 18 | padding/Spacer 4 或 8；图标 32、20 |
| ReceiveScreen.kt | 146：Spacer 14；219：vertical padding 14；291：Spacer 3；302：Spacer 6 | 16；16；2 或 4；8 |
| ReceiveStatusCard.kt | 67：Spacer 6 | 8 |
| SendScreen.kt | 220：padding 18×14；263：horizontal padding 18；269：iconSize 22；271：Spacer 14；282：Spacer 6 | 20×16；20；24；16；8 |
| SendSelection.kt | 174、197、315：vertical padding 10；179：spacedBy 10；215、463：Icon 22；419：Icon 18 | padding/gap 8 或 12；图标 24、20 |
| SettingsScreen.kt | 77、139、183：vertical padding 10；90：bottom padding 6；92、257：spacedBy 6；153：Spacer 6；292：vertical padding 6 | 8 或 12；4 或 8 |
| SharedFilesDialog.kt | 70：vertical padding 10 | 8 或 12；当前源码中未发现调用此对话框 |
| components/GlassPage.kt | 70：页面列表 spacedBy 14 | 16；影响发送、接收、设置三个页面 |
| components/MatterSurfaces.kt | 90：IconBubble 默认 iconSize 22；144：Spacer 6；160：vertical padding 14 | 24；8；16 |
| glass/LegadoLiquidFloatingBottomBar.kt | 81：BAR_HORIZONTAL_PADDING 18 | 16 或 20，并同步检查底栏总宽度 |
| components/FloatingBottomBar.kt | 45：BAR_BUTTON_GAP 10 | 8 或 12；当前主界面 showActions=false，暂不显示侧按钮 |

下列备用组件也违规，当前 Android 源码中未发现调用入口：

| 组件 | 行号与当前值 |
| --- | --- |
| components/EmptyMessage.kt | 82：spacedBy 10；112：horizontal padding 10；135：Icon 18；137：Spacer 6 |
| components/LiquidGlassTabRow.kt | 282：horizontal padding 10；289：top/end padding 7；290：圆点 size 6（状态标记而非图标，若统一所有标记尺寸则一并调整） |
| components/MatterSurfaces.kt 的 IdentityHeader | 46：iconSize 26；51：spacedBy 5 |
| components/OrbitHero.kt | 102：图标 size 38 |

明确排除：0.5dp 分隔线、0.6dp 高光、1dp 阴影、6dp 圆角、17dp 插画网格、拖动阈值、动画 translation 等不是 padding 或图标尺寸。Components.kt:228 的 6dp 是进度条高度，需按进度组件规范另外判断。

### 4. P2：设置开关同时在父行和 Switch 上处理切换

`components/SettingComponents.kt:199` 的父行通过 SettingItem 的 clickable 切换，并设置 Role.Switch/toggleableState；`:209` 起子 Switch 仍有非空 onCheckedChange。嵌套交互会形成两个切换入口，mergeDescendants 不能可靠合并独立可交互子节点。

建议：父行使用 toggleable(value=checked, role=Role.Switch, enabled=enabled, onValueChange=...)；子 Switch 设 onCheckedChange=null，仅呈现状态，让整行成为单一语义节点。这与 SendSelection.kt:313 已使用的父 toggleable + Checkbox(onCheckedChange=null) 模式一致。

### 5. P2：部分正文没有显式选择 style

明确的自由布局正文包括：ReceiveScreen.kt:226 文件名；SendSelection.kt:227 剪贴板空提示、247 剪贴板文件名、330 应用列表空提示；SharedFilesDialog.kt:63 空设备提示（备用入口）。这些不在 Button、TextField label 或 AlertDialog text 等提供专门文字样式的槽位内，容易落到默认 LocalTextStyle。

建议：正文统一显式 bodyMedium/bodyLarge，文件名按列表层级选择 bodyLarge。若项目要求每个 Text 都写 style，再补齐按钮 labelLarge、对话框标题 headlineSmall、对话框正文 bodyMedium 等。

以下省略 style 的位置通常已继承 M3 槽位样式，不能据此认定违反官方 M3：Button/TextButton 的 Text；AlertDialog title/text；OutlinedTextField label；DropdownMenuItem text。例如 ReceiveStatusCard.kt:90–94、Dialogs.kt:136/139/144、SettingsScreen.kt:290/295、SendScreen.kt:195/196/204。

### 6. P2：浅色 tertiary / onTertiary 配对对比度不足

`ui/Color.kt:30–31`：tertiary=#2AA9C9，onTertiary=#FFFFFF。按 sRGB 相对亮度计算为 **2.76:1**，不足普通小字 4.5:1，也低于 3:1。

当前只查到 tertiary 用于接收插画和备用 OrbitHero，未发现 onTertiary 文本调用，因此这是主题令牌缺陷，不能声称当前某个按钮白字已经不可读。建议通过 Material Theme Builder 生成配对 tonal palette，或调整这两个颜色以满足实际用途的对比度。

## 需要设备验证或作为设计建议的项目

1. **44dp 玻璃按钮的占位热区**：glass/LiquidGlassButton.kt:156/184、components/ImmersiveTopBar.kt:190。没有显式预留 48dp 布局空间。Compose clickable 通常自动扩展触摸区域，所以不能直接认定实际热区只有 44dp；建议保留 44dp 视觉，在外层预留至少 48dp，检查相邻命中范围、焦点和裁剪。
2. **备用 TabRow**：LiquidGlassTabRow 外高 44dp、内 padding 4dp，实际 tab 布局高约 36dp；TabChip 只有 clickable，没有 selected/Role.Tab/selectableGroup。启用前应补齐语义和至少 48dp 交互空间。
3. **大字体**：主底栏固定高 56dp，包含 24dp 图标、4dp 间距、文字；即使当前 labelSmall 行高 14sp，在 200% 字体下也约需 56dp，切换默认排版后更紧。顶栏也有固定 44dp 视觉容器。需要 100%/150%/200% 字体和窄窗口检查溢出、截断及控件可用性，优先使用 heightIn(min=...)。
4. **玻璃对比度**：底栏 surface alpha=0.40、备用 TabRow alpha=0.55、顶栏渐变以及正文额外 alpha，最终对比度取决于背后滚动内容。使用 colorScheme 并不自动保证半透明合成后的对比度。需深浅主题、动态色、滚动到高对比背景时截图测量；不足时提高表面不透明度或采用实色容器。
5. **键盘焦点反馈**：LiquidGlassIconButton 使用 indication=null，只有按压缩放，缺少明确的焦点/悬停反馈。建议通过 interactionSource 提供 focused/hovered 状态层。
6. **导航视觉**：当前底栏靠图标填充和颜色交叉渐变表示选中，未绘制标准 M3 导航项的 active indicator。若目标是贴近默认 M3E，可使用 NavigationBar/NavigationBarItem；保留品牌玻璃设计也可以，但应满足状态辨识和可访问性。
7. **自适应**：MainTabsScreen 固定手机式三页 + 底栏，未见窗口尺寸分级或宽屏导航切换。建议在平板/折叠屏验证内容最大宽度和 navigation rail；这属于适配建议，不是“手机界面必须改成侧栏”。

## 不应作为违规项

- 已使用 MaterialExpressiveTheme、MotionScheme.expressive、MaterialTheme.colorScheme、多处 Emphasized，具备 M3E 基础。
- 品牌色、动态取色开关、自定义 shapes、玻璃效果并不因“不是默认值”就违反 M3。
- 装饰图标 contentDescription=null 是正确用法；带文字的按钮也不必重复描述图标。
- 阴影 style.copy(shadow=...) 没有改变字号或字重。
- 不应把所有图标统一成 24dp：可按语义选择 16/20/24/28/32dp 等允许值；触摸目标要单独考虑。

## 建议实施顺序

1. 主底栏逐项交互语义、设置开关单节点化。
2. 默认 M3E Typography + 自由正文 style + 备用 EmptyMessage 的 faceTextSize 改为 TextStyle（当前 EmptyMessage.kt:70 为 30.sp，:103 直接指定 fontSize）。
3. 收敛 spacing/icon tokens，并按上表逐项修改；同步预览三个主要页面。
4. 修复色板配对；验证玻璃对比度、48dp 交互空间和大字体。
5. 清理或修复未使用组件，再考虑宽屏布局和更接近默认 M3E 的导航视觉。

## 官方依据

- [Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)：主题、排版层级及语义色。
- [Typography API](https://developer.android.com/reference/kotlin/androidx/compose/material3/Typography)：包括 labelSmallEmphasized 等强调排版样式。
- [Compose API defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)：48dp 目标、自动扩展、父容器承接选择交互及子控件回调置空。
- [Semantics in Compose](https://developer.android.com/develop/ui/compose/accessibility/semantics)：自定义组件需要补足交互、角色与状态语义。
- [Android accessibility](https://developer.android.com/guide/topics/ui/accessibility/apps)：文本对比度、大字体与可用性要求。
- [M3 spacing](https://m3.material.io/styles/spacing/overview)：间距体系；本报告的具体允许值以用户的项目约束为准。
