package run.halo.aipagebuilder.page;

import java.util.regex.Pattern;

/**
 * Replaces storage APIs that a sandboxed preview cannot reach. A sandboxed document has an
 * opaque origin, so reading {@code localStorage}, {@code sessionStorage} or
 * {@code document.cookie} throws a SecurityError. Themes read them while starting, and an
 * unguarded access aborts the rest of their scripts. The shim keeps the synchronous API
 * backed by memory, so values live only for the current preview document.
 */
final class PreviewStorageShim {
    // Comments are matched so a literal "<head>" inside one cannot capture the shim, and
    // quoted attribute values may contain ">" without ending the tag early. Possessive
    // quantifiers keep long attributes from recursing the matcher into a StackOverflowError.
    private static final Pattern HEAD = Pattern.compile(
        "<!--.*?-->|<head(?:\\s(?:[^>\"']++|\"[^\"]*+\"|'[^']*+')*+)?>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DOCTYPE =
        Pattern.compile("<!doctype[^>]*>", Pattern.CASE_INSENSITIVE);

    static final String BODY = """
        (function () {
          function memory() {
            var data = new Map();
            var api = {
              getItem: function (key) {
                key = String(key);
                return data.has(key) ? data.get(key) : null;
              },
              setItem: function (key, value) {
                data.set(String(key), String(value));
              },
              removeItem: function (key) {
                data.delete(String(key));
              },
              clear: function () {
                data.clear();
              },
              key: function (index) {
                var keys = Array.from(data.keys());
                return index >= 0 && index < keys.length ? keys[index] : null;
              }
            };
            Object.defineProperty(api, 'length', {
              get: function () { return data.size; },
              configurable: true
            });
            return new Proxy(api, {
              get: function (target, prop) {
                if (typeof prop === 'symbol') return target[prop];
                if (prop in target) return target[prop];
                // A missing item reads as undefined, like native storage; getItem keeps null.
                return data.has(String(prop)) ? data.get(String(prop)) : undefined;
              },
              set: function (target, prop, value) {
                api.setItem(prop, value);
                return true;
              },
              has: function (target, prop) {
                if (typeof prop === 'symbol') return prop in target;
                return prop in target || data.has(String(prop));
              },
              deleteProperty: function (target, prop) {
                if (typeof prop !== 'symbol') api.removeItem(prop);
                return true;
              },
              ownKeys: function () { return Array.from(data.keys()); },
              getOwnPropertyDescriptor: function (target, prop) {
                if (typeof prop === 'symbol') return undefined;
                if (prop in target) return Object.getOwnPropertyDescriptor(target, prop);
                if (!data.has(String(prop))) return undefined;
                return {
                  value: api.getItem(prop),
                  writable: true,
                  enumerable: true,
                  configurable: true
                };
              }
            });
          }
          ['localStorage', 'sessionStorage'].forEach(function (name) {
            try {
              if (window[name]) return;
            } catch (error) {
              // The sandbox rejects access; install the in-memory replacement below.
            }
            try {
              Object.defineProperty(window, name, { value: memory(), configurable: true });
            } catch (error) {
              // Leave the native error in place when the property cannot be replaced.
            }
          });
          try {
            void document.cookie;
          } catch (error) {
            var jar = Object.create(null);
            Object.defineProperty(document, 'cookie', {
              configurable: true,
              get: function () {
                return Object.keys(jar).map(function (key) {
                  return key + '=' + jar[key];
                }).join('; ');
              },
              set: function (value) {
                var parts = String(value).split(';');
                var pair = parts.shift();
                var index = pair.indexOf('=');
                if (index <= 0) return;
                var name = pair.slice(0, index).trim();
                // A past expiry deletes the cookie, as it would in a real jar. Max-Age wins
                // over Expires, and an attribute that does not parse is ignored.
                var maxAge = null;
                var expires = null;
                parts.forEach(function (attribute) {
                  var at = attribute.indexOf('=');
                  var key = (at < 0 ? attribute : attribute.slice(0, at)).trim().toLowerCase();
                  var raw = at < 0 ? '' : attribute.slice(at + 1).trim();
                  if (key === 'max-age' && /^-?\\d+$/.test(raw)) maxAge = Number(raw);
                  if (key === 'expires') {
                    var parsed = Date.parse(raw);
                    // Keep an earlier valid date when a repeated attribute does not parse.
                    if (!isNaN(parsed)) expires = parsed;
                  }
                });
                var expired = maxAge !== null ? maxAge <= 0 : expires !== null && expires < Date.now();
                if (expired) delete jar[name];
                else jar[name] = pair.slice(index + 1).trim();
              }
            });
          }
        })();
        """;

    private PreviewStorageShim() {
    }

    static String script() {
        return "<script data-ai-page-builder=\"preview-storage\">" + BODY + "</script>";
    }

    /** Inserts the shim as early as possible without reserializing the rest of the page. */
    static String inject(String html) {
        var head = HEAD.matcher(html);
        while (head.find()) {
            if (head.group().startsWith("<!--")) continue;
            return insertAt(html, head.end());
        }
        // Keep the doctype first so a document without <head> stays in standards mode.
        var doctype = DOCTYPE.matcher(html);
        return insertAt(html, doctype.find() ? doctype.end() : 0);
    }

    private static String insertAt(String html, int index) {
        return html.substring(0, index) + script() + html.substring(index);
    }
}
