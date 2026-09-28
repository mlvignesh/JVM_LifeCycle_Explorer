package jfr.model;

import java.awt.*;

public final class UiUtils {

    public static String esc(String s) {

        if (s == null) 
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public static String fmtDur(long ns) {

        if (ns <= 0) return "\u2014";
        if (ns < 1_000) return ns + " ns";
        if (ns < 1_000_000) return String.format("%.1f \u00b5s", ns / 1e3);
        if (ns < 1_000_000_000L) return String.format("%.2f ms", ns / 1e6);
        return String.format("%.2f s", ns / 1e9);
    }

    public static String fmtBytes(long b) {

        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1f MB", b / 1048576.0);
        return String.format("%.2f GB", b / 1073741824.0);
    }

    public static String simpleName(String cls) {

        int i = cls.lastIndexOf('.');
        return i >= 0 ? cls.substring(i + 1) : cls;
    }

    public static Color tint(Color c) {

        return new Color(255 - (255 - c.getRed()) / 6, 255 - (255 - c.getGreen()) / 6, 255 - (255 - c.getBlue()) / 6);
    }

    public static String hex(Color c) {
        
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
