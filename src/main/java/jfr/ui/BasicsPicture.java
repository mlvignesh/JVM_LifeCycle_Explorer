package jfr.ui;

import jfr.model.*;

import javax.swing.*;
import java.awt.*;
import java.util.*;

/** Draws Metaspace, Stack and Heap as three boxes. The box that just changed glows. */
public final class BasicsPicture extends JPanel {

    private static final int PAD = 12, ROW = 24, GAP = 4;
    private Story.Pic pic = new Story.Pic();

    public BasicsPicture() { setBackground(Color.WHITE); }

    public void setPic(Story.Pic p) {

        pic = p;
        repaint();
    }

    @Override public Dimension getPreferredSize() { return new Dimension(420, 380); }

    @Override protected void paintComponent(Graphics g0) {

        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font base = getFont() != null ? getFont() : new Font("SansSerif", Font.PLAIN, 12);
        int w = getWidth(), h = getHeight();

        g.setFont(base.deriveFont(Font.ITALIC, 12f));
        List<String> cap = wrap(g.getFontMetrics(), pic.caption, w - 2 * PAD);
        int capH = cap.isEmpty() ? 0 : cap.size() * 16 + 10;
        int bodyH = h - 2 * PAD - capH;
        if (bodyH < 120 || w < 200) { g.dispose(); return; }

        int stackW = (int) ((w - 3 * PAD) * 0.5);
        int rightW = w - 3 * PAD - stackW;
        int metaH = (bodyH - PAD) / 2;
        int heapH = bodyH - PAD - metaH;
        int rx = 2 * PAD + stackW;

        box(g, base, PAD, PAD, stackW, bodyH, "STACK", "what a thread is doing now", pic.stack, pic.stackHint, true, pic.glow == 1);
        box(g, base, rx, PAD, rightW, metaH, "METASPACE", "class blueprints (not the heap)", pic.meta, pic.metaHint, false, pic.glow == 0);
        box(g, base, rx, PAD + metaH + PAD, rightW, heapH, "HEAP", "every object created with new", pic.heap, pic.heapHint, false, pic.glow == 2);

        g.setFont(base.deriveFont(Font.ITALIC, 12f));
        g.setColor(new Color(0x37474F));
        int y = PAD + bodyH + 18;
        for (String line : cap) {

            g.drawString(line, PAD, y);
            y += 16;
        }
        g.dispose();
    }

    private void box(Graphics2D g, Font base, int x, int y, int w, int h, String title, String sub,
                     List<Story.Chip> chips, String hint, boolean bottomUp, boolean glow) {

        RoundRectangle2D r = new RoundRectangle2D.Double(x, y, w, h, 14, 14);
        g.setColor(glow ? new Color(0xFFFDE7) : new Color(0xFAFAFA));
        g.fill(r);
        g.setColor(glow ? new Color(0xFFB300) : new Color(0xB0BEC5));
        g.setStroke(new BasicStroke(glow ? 3f : 1.4f));
        g.draw(r);
        g.setStroke(new BasicStroke(1f));

        g.setFont(base.deriveFont(Font.BOLD, 12f));
        g.setColor(new Color(0x263238));
        g.drawString(title, x + 10, y + 18);
        g.setFont(base.deriveFont(Font.PLAIN, 10f));
        g.setColor(Color.GRAY);
        g.drawString(clip(g.getFontMetrics(), sub, w - 20), x + 10, y + 32);

        int top = y + 42, bottom = y + h - 8;
        int maxRows = Math.max(1, (bottom - top + GAP) / (ROW + GAP));
        if (chips.isEmpty()) {

            g.setFont(base.deriveFont(Font.ITALIC, 11f));
            g.setColor(new Color(0x90A4AE));
            List<String> lines = wrap(g.getFontMetrics(), hint, w - 24);
            int ty = top + 16;
            for (String l : lines) {
                
                g.drawString(l, x + 12, ty);
                ty += 15;
            }
            return;
        }

        List<Story.Chip> shown = new ArrayList<>(chips);
        String more = null;
        if (shown.size() > maxRows) {

            int extra = shown.size() - (maxRows - 1);
            if (bottomUp) {

                more = extra + " more frame(s) below";
                shown = new ArrayList<>(shown.subList(extra, shown.size()));
            } else {

                more = "+ " + extra + " more";
                shown = new ArrayList<>(shown.subList(0, maxRows - 1));
            }
        }
        int rowsToDraw = shown.size() + (more != null ? 1 : 0);
        for (int i = 0; i < rowsToDraw; i++) {

            int cy;
            if (bottomUp) cy = bottom - ROW - i * (ROW + GAP);
            else cy = top + i * (ROW + GAP);
            if (more != null && ((bottomUp && i == 0) || (!bottomUp && i == rowsToDraw - 1))) {

                g.setFont(base.deriveFont(Font.ITALIC, 10f));
                g.setColor(Color.GRAY);
                g.drawString(more, x + 12, cy + 16);
                continue;
            }
            int idx = bottomUp ? (more != null ? i - 1 : i) : i;
            chip(g, base, shown.get(idx), x + 8, cy, w - 16);
        }
        if (bottomUp && !shown.isEmpty()) {

            g.setFont(base.deriveFont(Font.BOLD, 10f));
            g.setColor(new Color(0x2E7D32));
            int n = shown.size() + (more != null ? 1 : 0);
            g.drawString("top of stack (running now)", x + 12, bottom - n * (ROW + GAP) - 2);
        }
    }

    private void chip(Graphics2D g, Font base, Story.Chip c, int x, int y, int w) {

        Color col = c.color();
        RoundRectangle2D r = new RoundRectangle2D.Double(x, y, w, ROW, 8, 8);
        int a = c.faded() ? 70 : 255;
        g.setColor(new Color(UiUtils.tint(col).getRed(), UiUtils.tint(col).getGreen(), UiUtils.tint(col).getBlue(), a));
        g.fill(r);
        g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), c.faded() ? 110 : 255));
        g.draw(r);

        g.setFont(base.deriveFont(Font.PLAIN, 10f));
        FontMetrics tfm = g.getFontMetrics();
        String tag = c.tag() == null ? "" : c.tag();
        int tagW = tag.isEmpty() ? 0 : tfm.stringWidth(tag) + 8;
        g.setFont(base.deriveFont(Font.BOLD, 11f));
        FontMetrics fm = g.getFontMetrics();
        String text = clip(fm, c.text(), w - 16 - tagW);
        g.setColor(c.faded() ? new Color(0x78909C) : Color.BLACK);
        g.drawString(text, x + 8, y + 16);
        if (c.struck()) {

            g.setColor(new Color(0xD32F2F));
            g.drawLine(x + 6, y + 12, x + 10 + fm.stringWidth(text), y + 12);
        }
        if (!tag.isEmpty()) {

            g.setFont(base.deriveFont(Font.PLAIN, 10f));
            g.setColor(c.struck() ? new Color(0xD32F2F) : col.darker());
            g.drawString(tag, x + w - 6 - tfm.stringWidth(tag), y + 16);
        }
    }

    private static String clip(FontMetrics fm, String s, int maxW) {

        if (fm.stringWidth(s) <= maxW) return s;
        while (s.length() > 4 && fm.stringWidth(s + "\u2026") > maxW) s = s.substring(0, s.length() - 1);
        return s + "\u2026";
    }

    private static List<String> wrap(FontMetrics fm, String text, int maxW) {

        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(trial) > maxW && line.length() > 0) {

                out.add(line.toString());
                line = new StringBuilder(word);
            } else
                line = new StringBuilder(trial);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
