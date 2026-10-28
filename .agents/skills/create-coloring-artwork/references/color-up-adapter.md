# Color Up项目接入

仅当前任务涉及Color Up实现时读取。项目根目录由当前工作区／用户选择确定，不能依赖原开发机器路径。先阅读项目AGENTS及现有导入接口；现行接口为 `tools/import_illustrations.py`生成、同命令 `--check`严格校验。不要新建另一款App或网站来代替实现。

## 清单与身份

`artwork/catalog.json`是唯一图库清单：

```json
{"schemaVersion":1,"expectedCount":1,"pages":[{"id":"new-artwork-id","title":"标题","theme":"custom","themeLabel":"主题名","sceneLabel":"场景","featured":false,"source":"generated/new-artwork-id/source.png","prompt":"generated/new-artwork-id/prompt.json","report":"generated/new-artwork-id/qa/report.json","geometry":"generated/new-artwork-id/geometry/new-artwork-id.regions.json","inkAsset":"illustrated/new-artwork-id.png"}]}
```

所有素材路径相对 `artwork/`，inkAsset相对Android assets。数量来自 `expectedCount`，不固定36或四系列。项目导入器验证原PNG、prompt、report、geometry的SHA一致、各图区域上限、ID／路径／洞／归一坐标，复制原PNG并生成核心几何；不存在占位／忽略缺图的正式模式。

已有ID对应的PNG和几何冻结。新画即使题材相同也用fresh新ID及新inkAsset；不能因为当前清单没有旧 `cat`就复用它接上早期存档。退役身份由项目的 `artwork/retired-ids.json`记录；删除素材与保留本机未知存档是不同操作，按用户意图处理，绝不自动把旧笔迹映射到新几何。

## 追加／替换并实现

先按通用流程完成真实QA与原图／mask视觉复核。注册工具会复制候选素材到项目 `artwork/generated/<新ID>/`，更新数量，并执行导入和strict check：

```sh
python3 "$COLORING_SKILL_DIR/scripts/add_to_catalog.py" \
  --candidate "$BUNDLE/candidate.json" \
  --catalog "$COLORING_PROJECT_ROOT/artwork/catalog.json" --project-root "$COLORING_PROJECT_ROOT"
```

可重复 `--candidate`一次接入一组图，只执行一轮hook。用户明确要替换某项时，单张使用 `--replace-id <旧ID>`；候选必须是不同的新ID，旧ID记入退役记录，不能覆盖旧ID几何。脚本不改本机存档，也不默认删除用户未要求删除的原素材。用户明确要求升级后清理旧素材时，完成新图接入和验证后，直接删除不再被活动清单引用的旧源及失败候选；只清理本项目中的明确文件，不删除品牌、退役ID登记或用户本机画作，也不新增旧图兼容入口。

若项目使用不同本地导入接口，可同时传 `--import-hook-json`和 `--check-hook-json`（JSON argv数组，禁shell拼接）。默认调用当前项目的Python导入器及 `--check`，不硬编码解释器或机器目录。hook失败会还原清单，并尝试重建旧清单对应的生成物；必须报告恢复失败，不能把清单写入当作实现完成。

完成后继续运行项目已有验证入口，检查真实显示／缩略图／PNG导出均消费原PNG，白底通过Multiply叠在色彩上；mask只锁区且保留洞，作品按新ID隔离。绘制/覆盖率/色板/撤销与存档规则沿用当前项目，不为新增主题重做产品流程。没有执行的设备验证不要声称成功。
