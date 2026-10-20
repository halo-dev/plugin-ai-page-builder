package run.halo.aipagebuilder.page;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import run.halo.aipagebuilder.Json;
import run.halo.aipagebuilder.model.PageDocument;
import tools.jackson.databind.JsonNode;

/** Versioned source package; uploaded entries are never extracted onto the filesystem. */
final class PageArchive {
    static final String FORMAT = "ai-page-builder";
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Set<String> SOURCE_FILES =
        Set.of("page.json", "page.html", "styles.css", "script.js");

    private PageArchive() { }

    record Metadata(String format, int version, String title, String description,
                    boolean themeLayout) { }

    record ImportedPage(PageDocument document, String title, String description, boolean themeLayout) { }

    static ImportedPage read(byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw invalid("ZIP 文件不能超过 4 MB");
        }
        var files = readEntries(bytes);
        if (!files.keySet().containsAll(SOURCE_FILES)) {
            throw invalid("页面包缺少 page.json、page.html、styles.css 或 script.js，请使用新版导出格式");
        }
        JsonNode metadata;
        try {
            var json = files.get("page.json");
            metadata = Json.fromJson(json.startsWith("﻿") ? json.substring(1) : json,
                JsonNode.class);
        } catch (RuntimeException error) {
            throw invalid("page.json 不是有效的 JSON");
        }
        if (metadata == null || !metadata.isObject()
            || !FORMAT.equals(metadata.path("format").asString())
            || !metadata.path("version").isIntegralNumber()
            || !metadata.path("version").canConvertToInt()
            || metadata.path("version").intValue() != 1) {
            throw invalid("不支持的页面包格式或版本");
        }
        if (!metadata.path("title").isString()
            || !metadata.path("themeLayout").isBoolean()
            || (metadata.hasNonNull("description") && !metadata.get("description").isString())) {
            throw invalid("page.json 的页面信息无效");
        }
        var document = new PageDocument(2, "code", null, null,
            files.get("page.html"), files.get("styles.css"), files.get("script.js"));
        var title = metadata.get("title").stringValue();
        var description = metadata.path("description").asString(null);
        PageService.validateProperties(title, description);
        return new ImportedPage(document, title, description, metadata.get("themeLayout").booleanValue());
    }

    private static Map<String, String> readEntries(byte[] bytes) {
        var files = new HashMap<String, String>();
        var names = new HashSet<String>();
        int total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                var name = entry.getName();
                while (name.startsWith("./")) {
                    name = name.substring(2);
                }
                if (names.size() >= 64 || !names.add(name)) {
                    throw invalid("ZIP 文件条目过多或包含重复文件");
                }
                if (name.startsWith("/") || name.contains("\\") || name.contains(":")
                    || java.util.Arrays.asList(name.split("/")).contains("..")) {
                    throw invalid("ZIP 包含无效的文件路径");
                }
                var content = zip.readNBytes(MAX_BYTES - total + 1);
                total += content.length;
                if (total > MAX_BYTES) {
                    throw invalid("ZIP 解压后的总大小不能超过 4 MB");
                }
                if (SOURCE_FILES.contains(name) && !entry.isDirectory()) {
                    if ("page.json".equals(name) && content.length > 16 * 1024) {
                        throw invalid("page.json 不能超过 16 KB");
                    }
                    var text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(content)).toString();
                    files.put(name, text);
                }
                zip.closeEntry();
            }
        } catch (IOException | IllegalArgumentException error) {
            throw invalid("ZIP 文件已损坏或内容不是有效的 UTF-8 文本");
        }
        return files;
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
