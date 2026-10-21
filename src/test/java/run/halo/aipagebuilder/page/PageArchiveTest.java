package run.halo.aipagebuilder.page;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PageArchiveTest {
    static Map<String, String> files() {
        var files = new LinkedHashMap<String, String>();
        files.put("page.json", """
            {"format":"ai-page-builder","version":1,"title":"共享页面",
             "description":"描述","themeLayout":false}
            """);
        files.put("page.html", "<p>Shared</p>");
        files.put("styles.css", "p {color: red}");
        files.put("script.js", "");
        return files;
    }

    static byte[] zip(Map<String, String> files) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void assertInvalid(byte[] bytes, String message) {
        assertThatThrownBy(() -> PageArchive.read(bytes))
            .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(error.getReason()).contains(message);
            });
    }

    @Test
    void acceptsIndependentlyCreatedPackagesAndIgnoresPreview() throws Exception {
        var files = files();
        files.put("index.html", "<html>Not imported</html>");
        var imported = PageArchive.read(zip(files));
        assertThat(imported.document().html()).isEqualTo("<p>Shared</p>");
        assertThat(imported.title()).isEqualTo("共享页面");
        assertThat(imported.description()).isEqualTo("描述");
        assertThat(imported.document().mode()).isEqualTo("code");
        assertThat(imported.document().projectData()).isNull();
        assertThat(imported.themeLayout()).isFalse();
        assertThat(imported.authorizeRequired()).isFalse();
    }

    @Test
    void readsOptionalAccessRestriction() throws Exception {
        var files = files();
        files.put("page.json", files.get("page.json").replace("\"themeLayout\":false",
            "\"themeLayout\":false,\"authorizeRequired\":true"));
        assertThat(PageArchive.read(zip(files)).authorizeRequired()).isTrue();
    }

    @Test
    void rejectsWrongAccessRestrictionType() throws Exception {
        var files = files();
        files.put("page.json", files.get("page.json").replace("\"themeLayout\":false",
            "\"themeLayout\":false,\"authorizeRequired\":\"true\""));
        assertInvalid(zip(files), "页面信息无效");
    }

    @Test
    void acceptsMetadataBomWithoutChangingSourceFiles() throws Exception {
        var files = files();
        files.put("page.json", "﻿" + files.get("page.json"));
        files.put("page.html", "﻿<p>Shared</p>");
        var imported = PageArchive.read(zip(files));
        assertThat(imported.title()).isEqualTo("共享页面");
        assertThat(imported.document().html()).isEqualTo(files.get("page.html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"./", "././"})
    void acceptsRelativeRootPrefixes(String prefix) throws Exception {
        var prefixed = new LinkedHashMap<String, String>();
        prefixed.put(prefix, "");
        files().forEach((name, value) -> prefixed.put(prefix + name, value));
        assertThat(PageArchive.read(zip(prefixed))).isEqualTo(PageArchive.read(zip(files())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page.json", "page.html", "styles.css", "script.js"})
    void rejectsDuplicatesAfterPathNormalization(String name) throws Exception {
        var files = files();
        files.put("./" + name, files.get(name));
        assertInvalid(zip(files), "重复文件");
    }

    @ParameterizedTest
    @ValueSource(strings = {"page.json", "page.html", "styles.css", "script.js"})
    void rejectsMissingSourceFilesAndLegacyExports(String missing) throws Exception {
        var files = files();
        files.remove(missing);
        assertInvalid(zip(files), "缺少");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{}",
        "{\"format\":\"ai-page-builder\",\"version\":2}",
        "{\"format\":\"ai-page-builder\",\"version\":4294967297}",
        "{\"format\":\"ai-page-builder\",\"version\":\"1\"}",
        "{\"format\":\"other\",\"version\":1}"})
    void rejectsInvalidOrUnsupportedMetadata(String metadata) throws Exception {
        var files = files();
        files.put("page.json", metadata);
        assertInvalid(zip(files), "");
    }

    @Test
    void rejectsWrongMetadataFieldTypes() throws Exception {
        var files = files();
        files.put("page.json", files.get("page.json").replace("false", "\"false\""));
        assertInvalid(zip(files), "页面信息无效");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../page.html", "/page.html", "nested/../page.html", "C:/page.html",
        "nested\\page.html", "./../page.html", ".//page.html", "./C:/page.html"})
    void rejectsUnsafePaths(String path) throws Exception {
        var files = files();
        files.put(path, "ignored");
        assertInvalid(zip(files), "文件路径");
    }

    @Test
    void rejectsDuplicateEntries() throws Exception {
        var files = files();
        files.put("fake.html", "duplicate");
        var bytes = zip(files);
        // Both names have equal byte length, including in the central directory.
        var fake = "fake.html".getBytes(StandardCharsets.UTF_8);
        var real = "page.html".getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index <= bytes.length - fake.length; index++) {
            if (java.util.Arrays.equals(bytes, index, index + fake.length, fake, 0, fake.length)) {
                System.arraycopy(real, 0, bytes, index, real.length);
            }
        }
        assertInvalid(bytes, "重复文件");
    }

    @Test
    void rejectsTooManyEntriesAndExcessiveExpandedSize() throws Exception {
        var files = files();
        for (int index = 0; index < 64; index++) {
            files.put("extra-" + index, "");
        }
        assertInvalid(zip(files), "条目过多");
        files = files();
        files.put("ignored.bin", "x".repeat(PageArchive.MAX_BYTES));
        assertInvalid(zip(files), "解压后的总大小");
        assertInvalid(new byte[PageArchive.MAX_BYTES + 1], "ZIP 文件不能超过");
    }

    @Test
    void rejectsOversizedMetadataAndTruncatedZipData() throws Exception {
        var files = files();
        files.put("page.json", " ".repeat(16 * 1024 + 1));
        assertInvalid(zip(files), "page.json 不能超过");
        assertInvalid(new byte[] {1, 2, 3}, "缺少");
        var bytes = zip(files());
        assertInvalid(java.util.Arrays.copyOf(bytes, 60), "损坏");
    }
}
