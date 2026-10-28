# v0.7.0 当前验证记录

日期：2026-10-28（Asia/Shanghai）。应用版本为 **0.7.0，versionCode 5**。本轮基线为提交 `3dd41d7`（v0.6.0），改进范围见[儿童游戏产品与交互审视](product-review.md)。当前36张插画、357个区域、108支蜡笔、锁区绘画规则和线稿技能未变；本轮没有进行真实儿童可用性测试。

## 构建与核心测试

最终最新 `./scripts/check.sh`已通过：108色调色板校验、插画导入器自检与严格校验、JVM核心测试、Android Lint、Debug APK及测试APK构建。日志为`artifacts/v07-final-check.txt`，结果`BUILD SUCCESSFUL`。

当前JUnit XML合计 **50项测试，0失败、0错误**。新增6项`ArtworkDiscoveryTest`验证实际区域数边界、主题与作品集合取交集、多个未完成作品、清空后完成历史、空集合计数及不修改输入。`EASY`按最多10区，是本产品的便捷筛选条件，不是年龄或能力评级；未知／退役存档ID不能成为图库条目，所有结果保持当前目录顺序。

Kotlin 2.2.21、AGP 8.13.2、Gradle 8.13、Compose BOM 2025.10.00；JVM目标17，compile/target SDK 36，minSdk 26。APK位于`app/build/outputs/apk/debug/app-debug.apk`。构建成功证明编译与静态检查通过，不能代替下面的设备运行。

## Android设备测试

最终APK在隔离`emulator-5556`完整运行 **69项测试全部通过，166.548秒**。日志为`artifacts/v07-final-device-tests.txt`，结果`OK (69 tests)`。

临时AVD为`ColorUp_ProductReview_API35`，使用已安装的API35 arm64镜像；就绪时确认2560×1600、density320、横屏`ROTATION_0`。动画保持设备默认值，未关闭动画。测试APK只安装到隔离设备，不在用户主模拟器执行破坏性测试。

```sh
adb -s emulator-5556 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5556 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r \
  com.colorup.app.test/androidx.test.runner.AndroidJUnitRunner
```

本轮设备运行通过的新增场景包括：

- 重来先确认；取消保留当前画作，确认后仍可撤销。
- 工具保持纯图标与用途描述，不带可见文字，绘画帮助可以再次打开；底部当前颜色不被指引替代。纸外初次指引不会因等待或无效起笔而消失，首次有效笔迹后退出。
- 单色与多色作品使用同等积极的庆祝呈现，不以灰星或少星比较优劣；底层覆盖率阈值、完成历史及颜色统计继续兼容。
- 作品集合与主题取交集，多个半成品均可找回；空集合能回到全部画稿。
- `ResponsiveLayoutTest`新增8项：1280×800、1024×768、800×1280、768×1024四种平板方向；600×960、500×800窄窗；长标题下首页按钮适配；颜色组在窗口改变与SavedInstanceState恢复后保留。

响应式测试使用真实Compose窗口约束，并把显示密度缩放到测试宿主能够容纳的范围。断言覆盖方形画板、画板不被工具／蜡笔遮挡、工具到画框14dp、控件至少48dp和18个颜色组位点可见。这部分验证的是窗口排布与可保存UI状态；真实Activity连续旋转与存档恢复另见下文。

原有设备测试继续覆盖真实蜡笔／炫彩纹理、全部活动区域完成可达性、PNG导出与相邻区域保护、108支蜡笔和18页循环、异步覆盖率、撤销／清空、单触点规则、v1/v2存档及坏档恢复、退役文件保护。这些既有场景也已纳入本轮69项并通过。

默认设备测试入口仍为`./scripts/gradle.sh :app:connectedDebugAndroidTest`。

## 原图与区域质量

本轮没有改动素材。v0.6.0已经完成的艺术与区域验证，继续作为当前未变内容的来源记录：

- [活动清单](../artwork/catalog.json)声明36张，四系列各9张，共357个可涂区域，单图5–17区。数量是当前内容统计，新增授权主题可修改清单，图库和测试不固定这些数量。
- 正式原PNG在`artwork/v2/source/`，完整生成／修改提示词与哈希在`artwork/v2/prompts/`，QA报告及编号区域预览在`artwork/v2/qa/`。全部由内置图像工具生成或定向修改。
- 全部满足每图不超过20个实质可涂区域（含背景），未检测出实质小窄块。原图和编号掩码均已实际检查，主体与背景闭合分离；未截取前20区或忽略有意义的小区来满足限制。
- 自动检查按真实原图尺寸提取连通区域、面积与最大内切圆，最小可容纳笔刷直径为44/1000设计单位；这不是每个色块在所有设备上的物理触控尺寸。
- 可见插画为原PNG，提取的EVEN_ODD轮廓只负责命中与裁剪；PNG用Multiply叠加，不由程序重画。v0.6.0的源PNG、工程资源及APK副本逐张SHA一致。本轮最终APK的严格导入校验已通过。

旧SVG、旧图库代码／入口及失败候选已在v0.6.0移除，本轮未恢复。退役ID登记只保留身份，未知存档不映射到新插画。

[36图总览](../artwork/v2/overview.png) · [原PNG与提示词压缩包](../artwork/v2/color-up-line-art-36.zip)

## 线稿技能的历史验证

[create-coloring-artwork](../.agents/skills/create-coloring-artwork/SKILL.md)本轮未修改。仓库副本、全局安装副本和便携ZIP已在v0.6.0逐文件核对一致；仓库版与全局版通过官方`quick_validate.py`。结构校验不替代真实生成与接入验证。

此前独立主题测试使用「小海龟送礼物」，保持5–12岁、最多20区，实际调用内置图像工具1次初始生成、2次定向编辑。最终1254×1254，**13个真实区域、0个实质小区、0个固定微小细节**，最小触径52.632/1000；原图与编号掩码经两人实际检查后导入独立内容测试工程，并核对哈希、原画不变及EVEN_ODD几何。

记录为`artifacts/skill-forward-project/validation/skill-forward-results.json`。该独立工程没有Android运行模块，未对小海龟验证设备绘制、实时裁剪、缩略图或PNG导出，也未将其加入当前36张正式图库。这是v0.6.0的技能验证，不冒称本轮重新生成或重新测试。

## 布局、旋转和主模拟器验收

最终APK已在真实模拟器窗口复查四种平板方向：1280×800、1024×768、800×1280、768×1024；画板保持正方形，工具与画框14dp、实际图标到画布内容22–24dp，触控至少48dp。竖屏左右工具组贴齐边缘，标题独立居中；横屏维持原来的紧凑工具组。界面与截图均实际查看，颜色胶囊在橡皮模式也保留当前颜色，画板无顶部品牌字或工具文字。

连续旋转0→90→0→270→0度后，当前画稿、选色、橡皮选中态、蜡笔页和原笔迹保持；新增一笔后旋转再撤销，精确恢复原3笔。记录为`artifacts/v07-rotation-results.json`及对应XML／存档快照。另检查系统导出取消不改变本机作品。

主模拟器已覆盖安装最终v0.7.0，最后一次升级前后**8个已有画作页文件，共20880字节逐项相同，原索引引用全部保留**。用户本轮新增画作也包含在核对内；没有向主模拟器注入演示数据。记录为`artifacts/v07-primary-save-check.json`，原始快照为`artifacts/primary-before-v07-delivery.tar`与`artifacts/primary-after-v07-delivery.tar`。

首页四个规格胶囊已去除；啵啵使用现有PNG轻浮动、微摆和呼吸，动画开启时两次原生界面采样的吉祥物边界不同，确认不是静态截图。主模拟器原先三个系统动画缩放均为0，本轮为展示动效恢复为默认1；产品在系统关闭动画时静止。画册返回使用26dp箭头、52dp触点。

[首页](screenshots/home.png) · [画册](screenshots/gallery.png) · [横屏画板](screenshots/paint.png) · [竖屏画板](screenshots/paint-portrait.png) · [1024×768](screenshots/paint-1024.png) · [768×1024](screenshots/paint-portrait-768.png)

截图演示与破坏性测试均只在独立模拟器进行。临时测试AVD已移除，用户主AVD与存档保留，最终APK已安装。

验证限本机构建与模拟器，不代表远端CI、真实平板触控笔／掌触／延迟、长期或满容量帧率、完整设备与无障碍矩阵、发行签名或商店审核已经通过。应用继续离线，无广告、账号、统计SDK、内购和危险存储权限。真实儿童测试仍需覆盖不同年龄段，检查能否独立开始、理解抬手换区、找回半成品及辨认重来／存图。
