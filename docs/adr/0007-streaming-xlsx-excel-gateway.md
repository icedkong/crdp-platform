# ADR 0007：流式 xlsx 读取网关（ExcelGateway 落地，JDK zip + StAX、不引入 POI）

- 状态：已接受（E18-T02 / A-PAT-018、A-PAT-008）
- 日期：2026-11-07
- 关联：`docs/requirements/tech-plan.md` §12「流式解析与转换：POI 事件模型逐行读取」、ADR 0002（不引第二套 UI 栈同理的依赖克制）、`SimpleXlsx` 既有取舍（E06-T03 起）、OQ-100④（E18-T01 预留的流式接入缝）、OQ-101（E18-T02 暂行口径）

## 背景与决策

E18-T02 落地患者历史数据导入的流式解析与暂存（imp_job / imp_row，验收「大文件不占满内存」：医院 A 宽表 5000 患者 × 761 列）。 按AGENTS
§6「`crdp-framework` 的 Unsupported\* 占位实现替换真实适配器、接口签名变更先写 ADR」，本 ADR 立案 `ExcelGateway` 从占位（`assertSupported()` 恒抛） 换成真实流式签名。

### 1. 端口签名

```java
ExcelWorkbook open(InputStream xlsx)          // 只解析工作表目录（workbook.xml + rels）
ExcelWorkbook.sheetNames() / sheet(name)      // 表可任意顺序读、可重复读（每次取新句柄）
ExcelSheet.header() / dataRows(BiConsumer)    // 表头先读一次；数据行逐行回调、单句柄单次
```

- 输入流先落**临时文件**再以 `java.util.zip.ZipFile` 随机访问：堆里不保留整份文件，`close()` 删临时文件；表句柄按需打开条目流。
- 结构性问题（缺 workbook.xml / 关系缺失 / 不是 zip）统一抛 `IllegalStateException("不是有效的 xlsx 文件…")`，消息面向用户（导入向导直接展示）。
- 删除 `UnsupportedExcelGateway`；小表一次性读写仍走 `SimpleXlsx`（OQ-52②），两者单元格口径一致（sharedStrings / inlineStr / 公式结果出文本，数值出原始文本，布尔出 TRUE / FALSE）。

### 2. 为什么不用 POI（对 tech-plan §12 的偏离说明）

tech-plan §12 写「POI 事件模型逐行读取」，本实现用 **JDK 内置 zip + StAX 事件**满足同一诉求，理由：

- 仓库既定取舍：`SimpleXlsx` 自 E06-T03 起只用 JDK zip + StAX 不引 POI，其 javadoc 明确「宽表流式读写属 E18 的框架
  ExcelGateway」——即 E18 落地的就是这套无 POI 方案的流式版（AGENTS §2：已落地代码优先于文档旧写法）；
- POI（poi-ooxml + 传递依赖）约十余 MB，而本场景只需要「事件模型逐行读」——StAX 本身就是事件模型，POI 的 SAX/XSSF
  事件 API 也是它的封装；写入端（模板下载、E18-T03 批注 Excel）沿用 `SimpleXlsx` 的直写方案；
- 单元格按 tech-plan §12「一律按文本读入再转换」处理，不需要 POI 的样式 / 日期解释（日期是 yyyymmdd 文本，转换与校验属 E18-T03）。

若后续需要 POI 才有的能力（如读取带样式的合并单元格信息做批注回写），在本 ADR 追加修订并评估引入。

### 3. 内存口径（验收「大文件不占满内存」的可验证定义）

- open 只读工作表目录；行数据每次回调一行、用完即弃——堆占用与文件行数 / 列数无关（除一行本身）；
- `sharedStrings` 首个共享单元格出现时一次性载入，上限 = 文件内**去重**字符串数（与行数无关；宽表随访值大量重复）；
- 验收测试 `StreamingXlsxMemoryTest`：受限堆（`-Xmx64m`）的子进程流式读完一张 2 万行 × 64 列的表（内容全量驻留约需
  130MB+），整表读入内存会 OOM、逐行流式正常完成并核对校验和。

### 4. 安全口径

- XXE 防护与 `SimpleXlsx` 同口径：DTD 与外部实体一律关闭（上传文件是不可信输入）；
- 行号取 `<row r>` 属性（缺省按上一行 +1 推算），列按 `r` 属性的列字母定位、行内缺口补空白——坐标口径与
  `ImportCell`（E18-T03 批注版报告的定位依据）一致。

## 后果

- `crdp-followup` importer 的 `StreamingImportWorkbook` 把本端口接成 E18-T01 的 `ImportSheetData`（OQ-100④ 兑现），
  版面适配器与整表内存路径同一模型；
- `ExcelGateway` 成为 Spring bean（`StreamingXlsxGateway`），上传 / 下载端点（E18-T06）与批注报告（E18-T03）直接注入；
- tech-plan §12 的「POI 事件模型」措辞按本 ADR 修订为「事件模型逐行读取（JDK zip + StAX）」，OQ-101 记录该暂行口径。
