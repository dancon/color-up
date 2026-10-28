# 通用素材制作

适用于只交付素材，或在接入任意项目之前建立独立素材包。脚本的目录均相对当前技能目录；以下 `COLORING_SKILL_DIR`应取实际加载的技能位置，`COLORING_WORK_DIR`取用户指定目录或当前工作区中的新产物目录。不要照抄任何机器的固定路径。

## 主题与生成

```sh
python3 "$COLORING_SKILL_DIR/scripts/prepare.py" \
  --theme '恐龙邮差' --scene '圆润小恐龙骑滑板送一封大信，欢乐招手' \
  --out "$COLORING_WORK_DIR"
```

可重复 `--theme`，或用 `--themes-json`传字符串／对象数组：对象可含 `title,scene,id,series,themeLabel,sceneLabel,featured`。无需固定图数或系列。`--age`、`--size`、`--max-regions`和目标范围可按用户要求调整。默认新ID含可读主题或摘要与随机后缀；图稿冻结后保持ID，重绘另建新版本。

工具输出每个 `candidate.json`路径。同目录有brief、完整prompt草案、待填源PNG、QA/geometry位置及未批准的review草案。读取并按用户明确细节修订prompt；机械主题重点避免轮圈双边、面框、窄接头、密窗格和螺钉。让动作与外轮廓表达趣味，不靠堆细节。

内置图像工具调用：新图提供 `prompt`、白底 `transparent_background:false`；没有参考时省略所有引用参数。工具有本机输出路径时，用下述命令复制选定PNG，保留工具原产物：

```sh
python3 "$COLORING_SKILL_DIR/scripts/record_artifact.py" \
  --candidate "$BUNDLE/candidate.json" --generated "$GENERATED_PNG"
```

不要把多张不同主题拼成一张联系表冒充交付。批量各图独立保存，提示词与实际生成／编辑调用保持一致；每次编辑前把真实修改要求写入prompt记录，再用同一文本调用内置工具。编辑先查看本机目标，用 `referenced_image_paths`指定它；修订草稿用record命令的 `--replace-candidate`，旧工具产物及哈希轨迹保留。记录新图会撤销旧的视觉批准。

## 真实区域分析

优先复用工作区已有且依赖可用的Python环境。先用 `command -v uv python3.12 python3`检查现有工具；若不在shell PATH，查看当前工作区提供的运行时信息，使用已发现的实际可执行文件路径，不猜机器位置。首次建立独立环境，后续复用；锁文件随技能携带：

```sh
uv venv "$COLORING_WORK_DIR/.venv" --python 3.12
uv pip sync "$COLORING_SKILL_DIR/scripts/requirements.lock" --python "$COLORING_WORK_DIR/.venv/bin/python"
"$COLORING_WORK_DIR/.venv/bin/python" "$COLORING_SKILL_DIR/scripts/analyze_illustration.py" --self-test
```

没有uv时，用已确认的Python3.12可执行文件建立环境，再由该环境的pip安装同一份带哈希锁：

```sh
python3.12 -m venv "$COLORING_WORK_DIR/.venv"
"$COLORING_WORK_DIR/.venv/bin/python" -m pip install --require-hashes -r "$COLORING_SKILL_DIR/scripts/requirements.lock"
"$COLORING_WORK_DIR/.venv/bin/python" "$COLORING_SKILL_DIR/scripts/analyze_illustration.py" --self-test
```

Windows使用该环境实际的 `Scripts/python.exe`。首次导入OpenCV／NumPy等原生依赖可能较慢，先区分依赖加载与区域分析耗时，不修改系统安全配置或QA门槛来绕过。分析命令中的ID须读取candidate真实ID，不能临时改名：

```sh
"$COLORING_WORK_DIR/.venv/bin/python" "$COLORING_SKILL_DIR/scripts/analyze_illustration.py" "$BUNDLE/source.png" \
  --summary --max-regions 20 --page-id "$ARTWORK_ID" \
  --report "$BUNDLE/qa/report.json" --preview "$BUNDLE/qa/regions-preview.png" \
  --export "$BUNDLE/geometry"
```

将 `--max-regions`设为brief中的真实上限。默认44/1000触径适配40单位笔宽，是工程默认值，不是用户规定；如用户笔宽／设备要求不同，应先据实际需求确定参数。不要仅为让失败候选变绿而调小它。用户明确要求非正方形时可用 `--allow-non-square`，消费端也必须支持对应比例。

退出0通过、2需修图、1为输入／处理错误。默认阈值160，派生掩码3×3闭合操作；只改分析掩码，不改原PNG。实质白区全量计数（包括背景）；仅同时满足极小面积／直径／包围盒且不接边的微小高光单独报告，不能任意把多余区域划为装饰。

## 人工复核与交付

用图像查看工具真正查看source和编号preview。确认主题和动作、自然线条、留白、全部主要形状闭合、主体与背景标签分离、无被漏掉的细圈／夹缝。自动pass仍可能有主体开口：例如蛋糕糖霜漏到背景，或星球环与天空共用标签。若不合格，用内置图像工具定向修线，重新记录PNG和全部QA。

实际看过后记录具体观察，脚本不会代看图片：

```sh
python3 "$COLORING_SKILL_DIR/scripts/record_review.py" --candidate "$BUNDLE/candidate.json" \
  --reviewed-original --reviewed-preview --subjects-closed \
  --notes '主体与背景标签独立，主要形状闭合；无双轮圈与细小夹缝，动作和白区适合当前年龄。'
```

交付整个候选包；`source.png`原样保留，prompt含实际文本、内置工具来源、哈希与版本轨迹。几何为 `EVEN_ODD`，保留洞，归一1000坐标；它不是可见矢量插画。只需素材时到此完成，不创建网站或项目manifest；要实现时继续对应项目接入。
