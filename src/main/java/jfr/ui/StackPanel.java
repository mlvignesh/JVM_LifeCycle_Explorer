package jfr.ui;

import jfr.model.Fr;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.*;

/** Draws a call stack the way textbooks do: one box per frame, current method on top. */
public final class StackPanel extends JPanel {

    private static final int BOX_H = 44, GAP = 6, PAD = 14;
    private List<Fr> frames = List.of();
    private String caption = "Select an event that has a stack trace (or wait for a live sample while a program runs).";
    private boolean showJdk = true;

    public StackPanel() { setBackground(Color.WHITE); }

    public void setShowJdk(boolean b) {

        showJdk = b;
        revalidate();
        repaint();
    }

    public void setFrames(String caption, List<Fr> f) {

        this.caption = caption;
        this.frames = f == null ? List.of() : f;
        revalidate();
        repaint();
    }

    private List<Fr> visible() {

        if (showJdk) return frames;
        List<Fr> v = new ArrayList<>();
        for (Fr f : frames) if (f.user()) v.add(f);
        return v;
    }

    @Override public Dimension getPreferredSize() {

        return new Dimension(320, 70 + visible().size() * (BOX_H + GAP));
    }

    @Override protected void paintComponent(Graphics g0) {

        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Font base = getFont() != null ? getFont() : new Font("SansSerif", Font.PLAIN, 12);
        g.setFont(base.deriveFont(Font.ITALIC, 11f));
        g.setColor(Color.DARK_GRAY);
        g.drawString(caption, PAD, 18);
        List<Fr> v = visible();
        if (v.isEmpty()) { g.dispose(); return; }
        int w = getWidth() - 2 * PAD, y = 30;
        g.setFont(base.deriveFont(Font.BOLD, 10f));
        g.setColor(new Color(0x2E7D32));
        g.drawString("TOP OF STACK - running right now", PAD, y + 8);
        y += 14;
        for (int i = 0; i < v.size(); i++) {

            Fr f = v.get(i);
            Color fill = f.user() ? new Color(0xE3F2FD) : new Color(0xF5F5F5);
            Color line = f.user() ? new Color(0x1E88E5) : new Color(0xBDBDBD);
            RoundRectangle2D box = new RoundRectangle2D.Double(PAD, y, w, BOX_H, 10, 10);
            g.setColor(fill);
            g.fill(box);
            g.setColor(i == 0 ? new Color(0x2E7D32) : line);
            g.setStroke(new BasicStroke(i == 0 ? 2.5f : 1.2f));
            g.draw(box);
            g.setColor(f.user() ? Color.BLACK : Color.DARK_GRAY);
            g.setFont(base.deriveFont(Font.BOLD, 12f));
            g.drawString(clip(g, f.simple() + "." + f.method() + "()", w - 100), PAD + 10, y + 18);
            g.setFont(base.deriveFont(Font.PLAIN, 10f));
            g.setColor(Color.GRAY);
            String sub = f.cls() + (f.line() > 0 ? " \u00b7 line " + f.line() : "") + " \u00b7 bci " + f.bci() + (f.kind().isEmpty() ? "" : " \u00b7 " + f.kind());
            g.drawString(clip(g, sub, w - 20), PAD + 10, y + 35);
            g.setFont(base.deriveFont(Font.BOLD, 10f));
            g.setColor(f.user() ? new Color(0x1E88E5) : Color.GRAY);
            String tag = f.user() ? "YOUR CODE" : "JDK";
            g.drawString(tag, PAD + w - 10 - g.getFontMetrics().stringWidth(tag), y + 16);
            y += BOX_H + GAP;
        }
        g.setColor(new Color(0x8D6E63));
        g.setFont(base.deriveFont(Font.BOLD, 10f));
        g.drawString("BOTTOM OF STACK - where the thread started", PAD, y + 8);
        g.dispose();
    }

    private static String clip(Graphics2D g, String s, int maxW) {
        
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) return s;
        while (s.length() > 4 && fm.stringWidth(s + "\u2026") > maxW) s = s.substring(0, s.length() - 1);
        return s + "\u2026";
    }
}
