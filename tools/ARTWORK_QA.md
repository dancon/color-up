# 原PNG插画与闭合区域检查

可见艺术是原PNG。分析工具只读取它，生成明确指定的QA预览与裁剪几何；不能用程序拼图、重画轮廓或截取前20区替代插画。

通用制作流程与可携带工具在[儿童涂色插画技能](../.agents/skills/create-coloring-artwork/SKILL.md)。唯一实现为该技能的 `scripts/analyze_illustration.py`，唯一依赖源为同目录 `requirements.in/requirements.lock`；本目录的Python入口和依赖文件只是薄包装，不另维护一份逻辑或锁。

## 环境与命令

```sh
uv venv tools/.venv-artwork --python 3.12
uv pip sync .agents/skills/create-coloring-artwork/scripts/requirements.lock --python tools/.venv-artwork/bin/python
tools/.venv-artwork/bin/python tools/analyze_illustration.py --self-test
```

从 `artwork/catalog.json`读取所选插画的实际ID与相对 `artwork/`的source/report/geometry路径。当前图稿示例：

```sh
tools/.venv-artwork/bin/python tools/analyze_illustration.py artwork/v2/source/cat.png \
  --summary --report artwork/v2/qa/cat.report.json \
  --preview artwork/v2/qa/cat.regions-preview.png \
  --export artwork/v2/regions --page-id art2-cat
python3 tools/import_illustrations.py --check
```

退出0表示自动检查通过，2表示候选需修图，1表示输入／处理错误。新素材的完整准备、内置图像生成／编辑、哈希记录、实际视觉检查、追加或显式替换清单及项目hook，均按技能流程执行；不要只交付候选而遗漏用户要求的实现。

## 验收含义

默认全图最多20个实质闭合白区（含背景），10–16为目标，更少也可。计数全量保留，不能删掉多余区域来凑数；只有同时满足极小面积、触径、包围盒且不接边的微小高光单独报告。窄机械圈、夹缝与有意义的小区仍会计数并拒绝。

分析默认阈值160，仅在派生墨线掩码上做3×3闭合，报告补缝量；补缝过多会拒绝。触径默认44/1000适配40单位笔宽，是工程选择，不是用户年龄或48dp的硬性定义。用户明确改变尺寸、比例、笔宽或区域上限时先确定真实要求，并同步消费项目规则，不能单为失败候选放宽参数。

必须实际查看原图和带编号掩码。计数通过不能证明专业构图或正确语义：主要角色、糖霜、星球环不能漏到背景。修改只用内置图像工具，保留专业外轮廓和动作，定向去掉窄边／夹缝；随后重测及复核。新技能报告还记录preview SHA，视觉复核绑定当前原图和当前掩码。

通过的几何包括全部区域及洞，`EVEN_ODD`、按真实源尺寸归一1000、`path_scale:1`；它只负责clip/hit。保留原PNG，消费端在涂色上以Multiply显示原墨线，或另派生墨线alpha；普通source-over白底PNG会盖住笔迹。缩略图降采样、全尺寸位图缓存有上限。

已有PNG／几何身份冻结，重绘使用新ID，不映射已有笔迹。`artwork/retired-ids.json`保留退役ID字符串，防止删除素材后又把早期存档挂到同名新图。清单数量由 `expectedCount`决定，不硬编码历史图数或系列。
