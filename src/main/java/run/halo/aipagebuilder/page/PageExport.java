package run.halo.aipagebuilder.page;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jsoup.nodes.Entities;
import run.halo.aipagebuilder.model.PageDocument;

/** Source export intentionally leaves online assets and theme dependencies as references. */
final class PageExport {
    private PageExport() { }

    static byte[] zip(PageDocument document) {
        var html = "<!doctype html>\n<html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>" + Entities.escape(document.title()) + "</title>"
            + "<link rel=\"stylesheet\" href=\"styles.css\"></head><body>\n"
            + document.html() + (document.js().isEmpty() ? "" : "\n<script src=\"script.js\"></script>")
            + "\n</body></html>\n";
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "index.html", html);
            entry(zip, "styles.css", document.css());
            if (!document.js().isEmpty()) entry(zip, "script.js", document.js());
            entry(zip, "README.txt", "AI Page Builder 页面源码导出\n\n"
                + "这是已保存草稿的 HTML/CSS/JavaScript，不含主题外壳、主题样式、附件文件、聊天或历史。\n"
                + "图片、字体、相对路径和主题变量可能需要原站点环境；离线使用前请调整资源地址。\n"
                + "主题亮暗根条件需由集成环境设置（如 html.dark），导出不会复制主题私有脚本。\n"
                + "script.js 在直接打开或部署此源码时会执行；Console 运行预览的 iframe 沙箱不随源码包提供。\n");
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("页面导出失败", error);
        }
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
