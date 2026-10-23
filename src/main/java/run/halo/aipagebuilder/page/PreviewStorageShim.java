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
    private static final Pattern HEAD =
        Pattern.compile("<head(?:\\s[^>]*)?>", Pattern.CASE_INSENSITIVE);

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
                return prop in target ? target[prop] : api.getItem(prop);
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
                var pair = String(value).split(';')[0];
                var index = pair.indexOf('=');
                if (index > 0) jar[pair.slice(0, index).trim()] = pair.slice(index + 1).trim();
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
        if (head.find()) {
            var end = head.end();
            return html.substring(0, end) + script() + html.substring(end);
        }
        return script() + html;
    }
}
