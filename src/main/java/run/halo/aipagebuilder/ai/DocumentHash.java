package run.halo.aipagebuilder.ai;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 of HTML then CSS, each prefixed by its four-byte big-endian UTF-8 byte length. */
public final class DocumentHash {
    private DocumentHash() {
    }

    public static String of(String html, String css) {
        return of(html, css, "");
    }

    public static String of(String html, String css, String js) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var fields = js == null || js.isEmpty() ? new String[] {html, css} : new String[] {html, css, js};
            for (var text : fields) {
                var bytes = text.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }
}
