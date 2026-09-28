package jfr.ui;

import jfr.model.UiUtils;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

/** Line chart of heap usage over time, with GC pauses and lifecycle steps marked. */
public final class HeapPanel extends JPanel {

    private List<long[]> samples = List.of();          // {ms, used, committed}
    private List<Object[]> marks = List.of();          // {ms, label, Color}

    public HeapPanel() { setBackground(Color.WHITE); }

    public void setData(List<long[]> s, List<Object[]> m) {

        samples = s;
        marks = m;
        repaint();
    }

    private static double niceStep(double raw) {

        if (raw <= 0) 
            return 1;
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

    @Override protected void paintComponent(Graphics g0) {

        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int L = 70, R = 24, T = 34, B = 36, w = getWidth(), h = getHeight();
        int pw = w - L - R, ph = h - T - B;
        Font base = getFont() != null ? getFont() : new Font("SansSerif", Font.PLAIN, 12);
        g.setFont(base.deriveFont(11f));
        if (samples.size() < 2 || pw < 50 || ph < 50) {

            g.setColor(Color.GRAY);
            g.drawString("Run a program to see the heap grow and shrink. Red lines are garbage collections.", L, T + 20);
            g.dispose();
            return;
        }
        double tMax = Math.max(50, samples.get(samples.size() - 1)[0]);
        long yMax = 1;
        for (long[] s : samples) yMax = Math.max(yMax, Math.max(s[1], s[2]));
        double ystep = niceStep(yMax / 1048576.0 / 5) * 1048576.0;
        double yTop = Math.ceil(yMax / ystep) * ystep;

        for (double v = 0; v <= yTop + 1; v += ystep) {

            int y = (int) (T + ph - v / yTop * ph);
            g.setColor(new Color(0xE8E8E8));
            g.drawLine(L, y, L + pw, y);
            g.setColor(Color.DARK_GRAY);
            String lbl = UiUtils.fmtBytes((long) v);
            g.drawString(lbl, L - 8 - g.getFontMetrics().stringWidth(lbl), y + 4);
        }
        double xstep = niceStep(tMax / Math.max(1, pw / 80.0));
        for (double t = 0; t <= tMax; t += xstep) {

            int x = (int) (L + t / tMax * pw);
            g.setColor(new Color(0xF0F0F0));
            g.drawLine(x, T, x, T + ph);
            g.setColor(Color.DARK_GRAY);
            String lbl = fmtMs(t);
            g.drawString(lbl, x - g.getFontMetrics().stringWidth(lbl) / 2, T + ph + 16);
        }
        g.setColor(Color.GRAY);
        g.drawRect(L, T, pw, ph);

        java.awt.geom.Path2D.Double used = new java.awt.geom.Path2D.Double();
        java.awt.geom.Path2D.Double comm = new java.awt.geom.Path2D.Double();
        boolean first = true;
        double lastX = L;
        for (long[] s : samples) {

            double x = L + s[0] / tMax * pw;
            double yu = T + ph - s[1] / yTop * ph, yc = T + ph - s[2] / yTop * ph;
            if (first) { used.moveTo(x, yu); comm.moveTo(x, yc); first = false; }
            else { used.lineTo(x, yu); comm.lineTo(x, yc); }
            lastX = x;
        }
        java.awt.geom.Path2D.Double fill = new java.awt.geom.Path2D.Double(used);
        fill.lineTo(lastX, T + ph);
        fill.lineTo(L + samples.get(0)[0] / tMax * pw, T + ph);
        fill.closePath();
        g.setColor(new Color(30, 136, 229, 50));
        g.fill(fill);
        g.setColor(new Color(0x9E9E9E));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{5f, 4f}, 0f));
        g.draw(comm);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(0x1E88E5));
        g.draw(used);

        g.setStroke(new BasicStroke(1f));
        FontMetrics fm = g.getFontMetrics();
        int[] rowEnd = new int[3];
        for (Object[] m : marks) {

            double ms = (Double) m[0];
            if (ms < 0 || ms > tMax) continue;
            int x = (int) (L + ms / tMax * pw);
            Color c = (Color) m[2];
            boolean isGc = "GC".equals(m[1]);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), isGc ? 170 : 130));
            if (!isGc) g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 4f}, 0f));
            g.drawLine(x, T, x, T + ph);
            g.setStroke(new BasicStroke(1f));
            if (!isGc) {

                String label = (String) m[1];
                for (int r = 0; r < 3; r++) {

                    if (rowEnd[r] <= x) {

                        g.setColor(c.darker());
                        g.drawString(label, x + 3, T + 12 + r * 12);
                        rowEnd[r] = x + fm.stringWidth(label) + 8;
                        break;
                    }
                }
            }
        }

        String[] names = {"heap used", "heap committed (reserved from the OS)", "garbage collection"};
        Color[] cols = {new Color(0x1E88E5), new Color(0x9E9E9E), new Color(0xE53935)};
        int lx = L + 8, ly = 16;
        for (int i = 0; i < names.length; i++) {
            
            g.setColor(cols[i]);
            g.fillRect(lx, ly - (i == 2 ? 10 : 8), i == 2 ? 3 : 14, i == 2 ? 12 : 3);
            g.setColor(Color.DARK_GRAY);
            g.drawString(names[i], lx + 20, ly);
            lx += 20 + fm.stringWidth(names[i]) + 24;
        }
        g.dispose();
    }
}
