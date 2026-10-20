package ai.jobmatcher.jobs;

import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/**
 * Converts job-ad HTML to plain text. Only text is stored, so nothing from a posting can ever be rendered as
 * markup, and scripts and styles are dropped with their contents.
 */
public final class HtmlText {

    private static final Safelist STRUCTURE = Safelist.none().addTags("p", "br", "li", "ul", "ol", "div", "h1", "h2", "h3", "h4");
    private static final Set<String> BLOCKS = Set.of("p", "div", "h1", "h2", "h3", "h4", "ul", "ol");

    private HtmlText() {
    }

    public static String toText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = extract(html);
        // Some feeds (Arbeitnow, Greenhouse) entity-encode their HTML, so the first pass yields literal tags.
        if (text.matches("(?s).*</?(p|br|ul|li|div|strong|h[1-6])\\b[^>]*>.*")) {
            text = extract(text);
        }
        return text.replaceAll("[ \\t\\u00A0]+", " ").replaceAll(" ?\\n ?", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static String extract(String html) {
        Element body = Jsoup.parse(Jsoup.clean(html, STRUCTURE)).body();
        StringBuilder sb = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode t) {
                    sb.append(t.getWholeText().replaceAll("\\s+", " "));
                } else if (node instanceof Element e) {
                    String name = e.normalName();
                    if (name.equals("br")) {
                        sb.append('\n');
                    } else if (name.equals("li")) {
                        sb.append("\n• ");
                    } else if (BLOCKS.contains(name)) {
                        sb.append('\n');
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element e && (BLOCKS.contains(e.normalName()) || e.normalName().equals("li"))) {
                    sb.append('\n');
                }
            }
        }, body);
        return sb.toString();
    }
}
