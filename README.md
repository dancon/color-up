# 涂个彩 · Color Up!

面向5–12岁儿童的Android平板涂色游戏。当前只保留36张新版插画，共357个可涂区域，单图5–17区，硬上限20（含背景）；提供108支蜡笔（81实色、27炫彩），保留锁区自由涂画、纸面颗粒与渐变闪粉。

![Android画板](docs/screenshots/paint.png)

采用Kotlin + Jetpack Compose + Canvas，核心规则为独立JVM模块。[设计与来源](docs/design-reference.md)、[产品与交互审视](docs/product-review.md)、[工程决策](docs/engineering-decisions.md)、[验证记录](docs/verification.md)。旧SVG、旧图库生成代码、旧入口和失败候选素材已移除；品牌和音效仍在使用。

## 运行与检查

Android Studio打开仓库根目录，Gradle JDK使用Studio自带JBR或JDK17+，安装SDK36和Build-Tools36.0.0，运行`app`。最低API26。

```sh
./scripts/doctor.sh
./scripts/check.sh
./scripts/gradle.sh :app:connectedDebugAndroidTest
./scripts/gradle.sh :app:installDebug
```

`check.sh`核对108色调色板、插画清单与SHA，运行核心测试、Lint并构建APK和测试APK。实际执行结果见验证记录，配置CI不代表远端执行通过。APK位于`app/build/outputs/apk/debug/app-debug.apk`。

## 创建新线稿

可复用技能在[create-coloring-artwork](.agents/skills/create-coloring-artwork/SKILL.md)。在Codex中可直接提出：

> $create-coloring-artwork 以“小海龟送礼物”为主题，设计一张适合5–12岁的涂色线稿，并接入当前游戏，每张不超过20个大色块。

技能负责主题构思、内置图像生成／定向修改、实际区域数量与尺寸检测、原画和编号掩码人工复核、提示词与SHA记录，以及接入当前工程。支持单张和一组主题，不固定生成36张；也可仅交付素材。可见插画使用原PNG，提取轮廓只用于锁区，不用程序几何拼接原画。

当前清单为`artwork/catalog.json`，数量由`expectedCount`声明。新增主题通过清单和严格导入接入，不能覆盖已发布ID的几何，不能复用`artwork/retired-ids.json`中的退役身份。

```sh
python3 tools/import_palette.py --check
python3 tools/import_illustrations.py --self-test --check
# 改完清单后显式生成，不手改GeneratedIllustrations.kt
python3 tools/import_illustrations.py
```

QA命令、隔离依赖环境与判定规则见[ARTWORK_QA](tools/ARTWORK_QA.md)。当前36图的[总览](artwork/v2/overview.png)、[原图与提示词包](artwork/v2/color-up-line-art-36.zip)保留作交付物。

## 行为与存档

一笔锁定起笔区域，越界裁剪；橡皮同样锁区。笔宽40/1000，单触点，多指不接管，取消／旋转／切后台结束有效笔画。区域实际覆盖≥55%才算涂满，全部完成后首次庆祝，统一肯定创作；重来先确认，之后仍可撤销，完成历史保留。18页蜡笔每页6支，箭头循环，无颜色轮。

原始PNG、蜡笔／炫彩笔迹共用于画板、缩略图与1080×1200 PNG导出。画板使用纯图标工具，底部持续展示当前颜色，问号帮助可召回。画册可组合主题与轻松涂／接着画／我的作品筛选。画板与工具间隔14dp，支持平板横竖屏旋转及窄窗口，控件至少48dp。

存档采用按图不可变文件与最后原子提交的小索引，未改图不重写。支持现有格式v1/v2；未知或已退役图的本机文件保留以便恢复，不显示旧入口，不把旧笔迹套到新画稿。本轮清理不删除用户画作文件。新增图与几何需使用新ID，声音与选色在本机记忆。

应用无联网权限、广告、账号、统计SDK或内购。导出使用系统文件选择器，不申请危险存储权限。真实平板触控笔、掌触、长期／满容量帧率、完整设备矩阵和发行审核仍需独立验收。
