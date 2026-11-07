package com.crdp.framework.excel;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.stereotype.Component;

/**
 * {@link ExcelGateway} 的流式实现（E18-T02 / ADR 0007）：JDK 内置 zip + StAX，不引入 POI（沿用 SimpleXlsx 的既有取舍——
 * tech-plan §12「事件模型逐行读取」以 StAX 事件满足，选型说明见 ADR 0007）。
 *
 * <p>内存口径（验收「大文件不占满内存」）：open 只解析工作表目录；行数据每次回调一行、用完即弃； {@code sharedStrings}
 * 首个共享单元格出现时一次性载入——上限是文件内去重字符串数（与行数无关）。 输入流先落临时文件再按 zip 条目随机访问（表可任意顺序、可重复读），{@code close} 同时删临时文件。
 * XXE 防护与 {@link SimpleXlsx} 同口径（DTD 与外部实体一律关闭——上传文件是不可信输入）。 单元格类型解析口径也与 SimpleXlsx
 * 一致：sharedStrings / inlineStr / 公式结果出文本，数值出原始文本，布尔出 TRUE / FALSE。
 */
@Component
public class StreamingXlsxGateway implements ExcelGateway {

    private static final String NS_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    @Override
    public ExcelWorkbook open(InputStream xlsx) throws IOException {
        Path temp = Files.createTempFile("crdp-xlsx-", ".xlsx");
        ZipFile zip = null;
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                xlsx.transferTo(out);
            }
            zip = new ZipFile(temp.toFile(), StandardCharsets.UTF_8);
            Map<String, ZipEntry> sheets = sheetEntries(zip);
            return new WorkbookImpl(zip, temp, sheets);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(zip);
            deleteQuietly(temp);
            if (failure instanceof ZipStructureException structure) {
                throw new IllegalStateException(structure.getMessage(), failure);
            }
            if (failure instanceof IllegalStateException illegal) {
                throw illegal;
            }
            // ZipFile 对垃圾内容抛 ZipException（IOException）：统一转用户可读消息
            throw new IllegalStateException("不是有效的 xlsx 文件（无法读取工作簿目录）", failure);
        }
    }

    /** 结构性问题（携带用户可读消息，与「zip 本身打不开」区分）。 */
    private static final class ZipStructureException extends RuntimeException {

        ZipStructureException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- 工作簿

    private static final class WorkbookImpl implements ExcelWorkbook {

        private final ZipFile zip;
        private final Path temp;
        private final Map<String, ZipEntry> sheets;
        private List<String> sharedStrings;

        WorkbookImpl(ZipFile zip, Path temp, Map<String, ZipEntry> sheets) {
            this.zip = zip;
            this.temp = temp;
            this.sheets = sheets;
        }

        @Override
        public List<String> sheetNames() {
            return List.copyOf(sheets.keySet());
        }

        @Override
        public ExcelSheet sheet(String name) {
            ZipEntry entry = name == null ? null : sheets.get(name);
            return entry == null ? null : new SheetImpl(this, entry);
        }

        @Override
        public void close() throws IOException {
            try {
                zip.close();
            } finally {
                deleteQuietly(temp);
            }
        }

        InputStream entryStream(ZipEntry entry) throws IOException {
            return zip.getInputStream(entry);
        }

        /** sharedStrings 惰性整体载入（上限 = 文件内去重字符串数；无该条目则空表）。 */
        List<String> sharedStrings() throws IOException {
            if (sharedStrings == null) {
                ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
                sharedStrings = entry == null ? List.of() : readSharedStrings(entryStream(entry));
            }
            return sharedStrings;
        }
    }

    // ---------------------------------------------------------------- 工作表（StAX 逐行）

    private static final class SheetImpl implements ExcelSheet {

        private final WorkbookImpl workbook;
        private final ZipEntry entry;

        private InputStream stream;
        private XMLStreamReader reader;
        private List<String> header;
        private boolean rowsConsumed;

        /** 最近一次产出的行号（行元素缺 r 属性时按 +1 推算）。 */
        private int lastRowNo;

        SheetImpl(WorkbookImpl workbook, ZipEntry entry) {
            this.workbook = workbook;
            this.entry = entry;
        }

        @Override
        public List<String> header() throws IOException {
            if (header != null) {
                return header;
            }
            ensureOpen();
            header = nextRow();
            return header;
        }

        @Override
        public void dataRows(BiConsumer<Integer, List<String>> rowConsumer) throws IOException {
            if (rowsConsumed) {
                throw new IllegalStateException("同一张表的数据行只能遍历一次，请重新 sheet(name) 取新句柄");
            }
            rowsConsumed = true;
            ensureOpen();
            if (header == null) {
                header = nextRow(); // 未先调 header()：隐式消费表头行
            }
            List<String> row;
            while ((row = nextRow()) != null) {
                rowConsumer.accept(lastRowNo, row);
            }
        }

        private void ensureOpen() throws IOException {
            if (reader != null) {
                return;
            }
            stream = workbook.entryStream(entry);
            try {
                reader = factory().createXMLStreamReader(stream, StandardCharsets.UTF_8.name());
            } catch (XMLStreamException failure) {
                closeReader();
                throw new IllegalStateException("不是有效的 xlsx 文件（工作表解析失败）", failure);
            }
        }

        /** 下一行；文档读完返回 {@code null}（同时释放读取器）。产出行的行号落在 {@link #lastRowNo}。 */
        private List<String> nextRow() throws IOException {
            if (reader == null) {
                return null;
            }
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT
                            && "row".equals(reader.getLocalName())) {
                        return readRow();
                    }
                    // sheetData / dimension / cols 等其余元素跳过（行只出现一次）
                }
                closeReader();
                return null;
            } catch (XMLStreamException failure) {
                closeReader();
                throw new IllegalStateException("不是有效的 xlsx 文件（工作表解析失败）", failure);
            }
        }

        /** 读一个 &lt;row&gt; 的全部单元格（游标停在行尾元素上）。 */
        private List<String> readRow() throws XMLStreamException, IOException {
            String rowRef = attr(reader, "r");
            int rowNo = rowRef == null ? lastRowNo + 1 : Integer.parseInt(rowRef.strip());
            List<String> cells = new ArrayList<>();
            String cellType = null;
            Integer columnIndex = null;
            StringBuilder cellText = null;
            int sequentialColumn = 0;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    switch (local) {
                        case "c" -> {
                            cellType = attr(reader, "t");
                            String ref = attr(reader, "r");
                            columnIndex = ref == null ? null : columnIndex(ref);
                            cellText = new StringBuilder();
                        }
                        case "v", "t" -> {
                            if (cellText != null) {
                                cellText.append(reader.getElementText());
                            }
                        }
                        default -> {
                            // 公式（f）等其余子元素忽略
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("c".equals(local)) {
                        if (cellText != null) {
                            int target = columnIndex == null ? sequentialColumn : columnIndex;
                            while (cells.size() < target) {
                                cells.add("");
                            }
                            cells.add(cellValue(cellType, cellText.toString()));
                            sequentialColumn = target + 1;
                        }
                        cellText = null;
                        cellType = null;
                        columnIndex = null;
                    } else if ("row".equals(local)) {
                        lastRowNo = rowNo;
                        return cells;
                    }
                }
            }
            lastRowNo = rowNo;
            return cells; // 文档在行中途截断：给出已读部分（坏文件由上层的整体校验兜底）
        }

        /** 单元格类型 → 文本（与 SimpleXlsx.cellValue 同口径）。 */
        private String cellValue(String type, String raw) throws IOException {
            if ("s".equals(type)) {
                int index = Integer.parseInt(raw.trim());
                List<String> shared = workbook.sharedStrings();
                return index >= 0 && index < shared.size() ? shared.get(index) : "";
            }
            if ("b".equals(type)) {
                return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
            }
            return raw;
        }

        private void closeReader() {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                    // 工作簿 close 会兜底释放
                }
                reader = null;
            }
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // 同上
                }
                stream = null;
            }
        }
    }

    // ---------------------------------------------------------------- 结构解析

    /** workbook.xml + rels → 表名 → zip 条目（文件内顺序）。 */
    private static Map<String, ZipEntry> sheetEntries(ZipFile zip) throws IOException {
        Map<String, String> nameToRid = workbookSheets(zip);
        Map<String, String> ridToTarget = workbookRels(zip);
        Map<String, ZipEntry> sheets = new LinkedHashMap<>();
        for (Map.Entry<String, String> sheet : nameToRid.entrySet()) {
            String target = ridToTarget.get(sheet.getValue());
            if (target == null) {
                throw new ZipStructureException("不是有效的 xlsx 文件（工作表「" + sheet.getKey() + "」的关系缺失）");
            }
            ZipEntry entry = zip.getEntry(target);
            if (entry == null) {
                throw new ZipStructureException("不是有效的 xlsx 文件（工作表「" + sheet.getKey() + "」的内容缺失）");
            }
            sheets.put(sheet.getKey(), entry);
        }
        return sheets;
    }

    /** xl/workbook.xml 的 &lt;sheet name r:id&gt;（文件内顺序）。 */
    private static Map<String, String> workbookSheets(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("xl/workbook.xml");
        if (entry == null) {
            throw new ZipStructureException("不是有效的 xlsx 文件（缺 xl/workbook.xml）");
        }
        Map<String, String> sheets = new LinkedHashMap<>();
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader reader =
                    factory().createXMLStreamReader(in, StandardCharsets.UTF_8.name());
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "sheet".equals(reader.getLocalName())) {
                    String name = attr(reader, "name");
                    String rid = attr(reader, "id", NS_REL);
                    if (name == null || name.isBlank() || rid == null) {
                        throw new ZipStructureException("不是有效的 xlsx 文件（工作表目录不完整）");
                    }
                    sheets.put(name.strip(), rid);
                }
            }
            reader.close();
        } catch (XMLStreamException failure) {
            throw new ZipStructureException("不是有效的 xlsx 文件（工作簿目录解析失败）");
        }
        return sheets;
    }

    /** xl/_rels/workbook.xml.rels 的 rId → 工作表条目名（相对目标归一到 xl/ 前缀）。 */
    private static Map<String, String> workbookRels(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("xl/_rels/workbook.xml.rels");
        if (entry == null) {
            throw new ZipStructureException("不是有效的 xlsx 文件（缺工作簿关系）");
        }
        Map<String, String> rels = new LinkedHashMap<>();
        try (InputStream in = zip.getInputStream(entry)) {
            XMLStreamReader reader =
                    factory().createXMLStreamReader(in, StandardCharsets.UTF_8.name());
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT
                        && "Relationship".equals(reader.getLocalName())
                        && (NS_REL + "/worksheet").equals(attr(reader, "Type"))) {
                    String id = attr(reader, "Id");
                    String target = attr(reader, "Target");
                    if (id != null && target != null) {
                        rels.put(id, target.startsWith("/") ? target.substring(1) : "xl/" + target);
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException failure) {
            throw new ZipStructureException("不是有效的 xlsx 文件（工作簿关系解析失败）");
        }
        return rels;
    }

    /** sharedStrings：&lt;si&gt; 内直接 &lt;t&gt; 或富文本 &lt;r&gt;&lt;t&gt; 拼接（与 SimpleXlsx 同口径）。 */
    private static List<String> readSharedStrings(InputStream in) {
        List<String> strings = new ArrayList<>();
        try {
            XMLStreamReader reader =
                    factory().createXMLStreamReader(in, StandardCharsets.UTF_8.name());
            StringBuilder current = null;
            boolean inShared = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("sst".equals(local)) {
                        inShared = true;
                    } else if (inShared && "si".equals(local)) {
                        current = new StringBuilder();
                    } else if (current != null && "t".equals(local)) {
                        current.append(reader.getElementText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("si".equals(reader.getLocalName()) && current != null) {
                        strings.add(current.toString());
                        current = null;
                    }
                }
            }
            reader.close();
            return strings;
        } catch (XMLStreamException failure) {
            throw new ZipStructureException("不是有效的 xlsx 文件（sharedStrings 解析失败）");
        }
    }

    // ---------------------------------------------------------------- 工具

    private static String attr(XMLStreamReader reader, String name) {
        return attr(reader, name, null);
    }

    private static String attr(XMLStreamReader reader, String name, String namespace) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            boolean namespaceMatches =
                    namespace == null || namespace.equals(reader.getAttributeNamespace(i));
            if (namespaceMatches && reader.getAttributeLocalName(i).equals(name)) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /** 列字母 → 0 起始下标（A=0、Z=25、AA=26；与 SimpleXlsx / ImportCell 同口径）。 */
    private static int columnIndex(String cellRef) {
        int index = 0;
        for (char c : cellRef.toCharArray()) {
            if (c >= 'A' && c <= 'Z') {
                index = index * 26 + (c - 'A' + 1);
            } else {
                break;
            }
        }
        return index - 1;
    }

    private static void closeQuietly(ZipFile zip) {
        if (zip != null) {
            try {
                zip.close();
            } catch (IOException ignored) {
                // 打开失败的兜底清理
            }
        }
    }

    private static void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException ignored) {
            // 临时文件清理失败交给系统临时目录策略
        }
    }

    private static XMLInputFactory factory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        // 外部实体解析一律关闭（上传文件是不可信输入，与 SimpleXlsx 同口径）
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }
}
