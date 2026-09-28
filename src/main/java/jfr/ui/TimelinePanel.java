package jfr.ui;

import jfr.model.Category;
import jfr.model.Ev;
import jfr.model.UiUtils;

import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Swim-lane timeline: one lane per phase, time on the X axis. Click an event to inspect it. */
public final class TimelinePanel extends JPanel {

    private static final int LEFT = 150, TOP = 60, LANE_H = 30, RIGHT = 24;
    private List<Ev> events = List.of();
    private Instant origin = Instant.now();
    private final List<Object[]> hits = new ArrayList<>();
    private Consumer<Ev> onSelect = e -> { };
    private Ev hover;
    private double zoom = 1;

    public TimelinePanel() {

        setBackground(Color.WHITE);
        ToolTipManager.sharedInstance().registerComponent(this);
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {

                Ev h = hit(e.getPoint());
                if (h != hover) {

                    hover = h;
                    setCursor(Cursor.getPredefinedCursor(h == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
                    repaint();
                }
            }
        });
        addMouseListener(new MouseAdapter() {

            @Override public void mouseClicked(MouseEvent e) {

                Ev h = hit(e.getPoint());
                if (h != null) onSelect.accept(h);
            }
        });
    }

    public void setOnSelect(Consumer<Ev> c) { onSelect = c; }

    public void setZoom(double z) {

        zoom = z;
        revalidate();
        repaint();
    }

    public void setData(List<Ev> evs, Instant origin) {

        this.events = evs;
        this.origin = origin;
        repaint();
    }

    @Override public Dimension getPreferredSize() {

        int vw = getParent() instanceof JViewport vp ? vp.getWidth() : 900;
        return new Dimension((int) Math.max(600, vw * zoom), TOP + Category.values().length * LANE_H + 30);
    }

    private double rel(Ev e) { return Duration.between(origin, e.start).toNanos() / 1e6; }

    private Ev hit(Point p) {

        for (int i = hits.size() - 1; i >= 0; i--) {
            Object[] h = hits.get(i);
            if (((Rectangle2D) h[0]).contains(p)) return (Ev) h[1];
        }
        return null;
    }

    private static double niceStep(double raw) {

        if (raw <= 0) return 1;
        double exp = Math.floor(Math.log10(raw));
        double frac = raw / Math.pow(10, exp);
        double nice = frac < 1.5 ? 1 : frac < 3 ? 2 : frac < 7 ? 5 : 10;
        return nice * Math.pow(10, exp);
    }

    private static String fmtMs(double ms) {

        if (ms < 1000) return String.format("%.0f ms", ms);
        if (ms < 60_000) return String.format("%.2f s", ms / 1000.0);
        return String.format("%.1f min", ms / 60000.0);
    }

    @Override public String getToolTipText(MouseEvent me) {

        Ev e = hit(me.getPoint());
        if (e == null) return null;
        String d = e.details.length() > 220 ? e.details.substring(0, 220) + "\u2026" : e.details;
        return "<html><body style='width:380px'><b>" + UiUtils.esc(e.type) + "</b> - " + UiUtils.esc(e.target) + "<br>at +" + fmtMs(rel(e))
                + (e.durNs > 0 ? " \u00b7 " + UiUtils.fmtDur(e.durNs) : "") + "<br>" + UiUtils.esc(d) + "<br><i>click to inspect</i></body></html>";
    }

    @Override protected void paintComponent(Graphics g0) {

        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        hits.clear();
        Category[] cats = Category.values();
        int w = getWidth(), plotW = Math.max(50, w - LEFT - RIGHT), h = TOP + cats.length * LANE_H;
        Font base = getFont() != null ? getFont() : new Font("SansSerif", Font.PLAIN, 12);
        Font small = base.deriveFont(Font.PLAIN, 10f);

        double min = 0, max = 50;
        for (Ev e : events) {

            double s = rel(e);
            min = Math.min(min, s);
            max = Math.max(max, s + e.durNs / 1e6);
        }
        double span = Math.max(1, (max - min) * 1.03);
        final double fmin = min, fspan = span;
        java.util.function.DoubleUnaryOperator X = ms -> LEFT + (ms - fmin) / fspan * plotW;

        int[] counts = new int[cats.length];
        for (Ev e : events) counts[e.cat.ordinal()]++;

        for (int i = 0; i < cats.length; i++) {

            g.setColor(i % 2 == 0 ? new Color(0xFAFAFA) : Color.WHITE);
            g.fillRect(0, TOP + i * LANE_H, w, LANE_H);
        }
        double step = niceStep(span / Math.max(1, plotW / 90.0));
        g.setFont(small);
        for (double t = Math.ceil(min / step) * step; t <= min + span; t += step) {

            int x = (int) X.applyAsDouble(t);
            g.setColor(new Color(0xE0E0E0));
            g.drawLine(x, TOP - 6, x, h);
            g.setColor(Color.DARK_GRAY);
            g.drawString(fmtMs(t), x + 3, TOP - 8);
        }
        g.setColor(Color.GRAY);
        g.drawLine(LEFT, TOP - 4, w - RIGHT, TOP - 4);

        int[] rowEnd = new int[3];
        g.setFont(small);
        FontMetrics sfm = g.getFontMetrics();
        for (Ev e : events) {

            if (!e.milestone) continue;
            int x = (int) X.applyAsDouble(rel(e));
            g.setColor(new Color(e.cat.color.getRed(), e.cat.color.getGreen(), e.cat.color.getBlue(), 110));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g.drawLine(x, 8, x, h);
            g.setStroke(new BasicStroke(1f));
            String label = e.type.replace("STEP ", "").replace(" \u00b7 ", " ");
            int tw = sfm.stringWidth(label) + 8;
            for (int r = 0; r < 3; r++) {

                if (rowEnd[r] <= x) {

                    g.setColor(e.cat.color.darker());
                    g.drawString(label, x + 3, 18 + r * 12);
                    rowEnd[r] = x + tw;
                    break;
                }
            }
        }

        for (Ev e : events) {

            int lane = e.cat.ordinal();
            double x0 = X.applyAsDouble(rel(e));
            double wpx = Math.max(5, e.durNs / 1e6 / fspan * plotW);
            double y = TOP + lane * LANE_H + (LANE_H - 14) / 2.0;
            Shape s = e.milestone ? diamond(x0, y + 7, 8) : new RoundRectangle2D.Double(x0, y, wpx, 14, 4, 4);
            Color c = e.cat.color;
            g.setColor(e.jdk ? new Color(c.getRed(), c.getGreen(), c.getBlue(), 90) : new Color(c.getRed(), c.getGreen(), c.getBlue(), 200));
            g.fill(s);
            g.setColor(c.darker());
            g.draw(s);
            if (e == hover) {

                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(2f));
                g.draw(s);
                g.setStroke(new BasicStroke(1f));
            }
            Rectangle2D b = s.getBounds2D();
            hits.add(new Object[]{new Rectangle2D.Double(b.getX() - 2, b.getY() - 2, b.getWidth() + 4, b.getHeight() + 4), e});
        }

        Rectangle vr = getVisibleRect();
        g.setFont(base.deriveFont(Font.BOLD, 11f));
        for (int i = 0; i < cats.length; i++) {

            g.setColor(new Color(255, 255, 255, 235));
            g.fillRect(vr.x, TOP + i * LANE_H, LEFT - 8, LANE_H);
            g.setColor(cats[i].color);
            g.fillRect(vr.x + 6, TOP + i * LANE_H + 8, 6, 14);
            g.setColor(Color.DARK_GRAY);
            g.drawString(cats[i].label + (counts[i] > 0 ? " (" + counts[i] + ")" : ""), vr.x + 18, TOP + i * LANE_H + 19);
        }
        if (events.isEmpty()) {

            g.setColor(Color.GRAY);
            g.setFont(base.deriveFont(13f));
            g.drawString("Run a program to see its lifecycle on a timeline.", LEFT + 20, TOP + 20);
        }
        g.dispose();
    }

    private static Shape diamond(double cx, double cy, double r) {
        
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        p.moveTo(cx, cy - r);
        p.lineTo(cx + r, cy);
        p.lineTo(cx, cy + r);
        p.lineTo(cx - r, cy);
        p.closePath();
        return p;
    }
}
