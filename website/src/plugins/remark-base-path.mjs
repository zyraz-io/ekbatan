import { visit } from "unist-util-visit";

/**
 * Remark plugin that prefixes absolute-path links (and link definitions) in
 * markdown / MDX with the site's configured `base` path. Lets us keep the source
 * portable across deployments — `[link](/learn/foo/)` resolves correctly whether
 * the site is served at the root of a subdomain (Cloudflare Pages, custom domain)
 * or under a subpath (`zyraz-io.github.io/ekbatan/`).
 *
 * @param {{ base?: string }} options - same `base` value passed to Astro's defineConfig
 */
export default function remarkBasePath({ base = "/" } = {}) {
    // Strip trailing slash so we prepend "/ekbatan" (not "/ekbatan/") to "/foo".
    const prefix = base.replace(/\/$/, "");
    // No-op when serving at root — every absolute path already resolves correctly.
    if (!prefix) return () => {};
    return (tree) => {
        visit(tree, ["link", "definition"], (node) => {
            const url = node.url;
            if (typeof url !== "string") return;
            // Only rewrite absolute internal paths. Skip:
            //   "//cdn.example.com/..."  (schema-relative)
            //   "https://..." / "mailto:..." / "#anchor" / "../relative"  (don't start with "/")
            if (!url.startsWith("/") || url.startsWith("//")) return;
            // Don't double-prefix if the link is already base-qualified.
            if (url === prefix || url.startsWith(prefix + "/")) return;
            node.url = prefix + url;
        });
    };
}
