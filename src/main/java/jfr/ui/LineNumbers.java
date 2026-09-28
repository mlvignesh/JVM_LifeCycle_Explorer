package jfr.ui;

import javax.swing.JComponent;
import javax.swing.JTextArea;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;

/** Line-number gutter for the source editor. */
public final class LineNumbers extends JComponent implements DocumentListener {

    private final JTextArea ta;

    public LineNumbers(JTextArea ta) {

        this.ta = ta;
        ta.getDocument().addDocumentListener(this);
        setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        setForeground(new Color(0x888888));
        setOpaque(true);
        setBackground(new Color(0xF3F3F3));
    }

    @Override public Dimension getPreferredSize() {

        int digits = String.valueOf(Math.max(99, ta.getLineCount())).length();
        return new Dimension(getFontMetrics(getFont()).charWidth('0') * digits + 14, ta.getHeight());
    }

    @Override protected void paintComponent(Graphics g) {

        g.setColor(getBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setFont(getFont());
        g.setColor(getForeground());
        FontMetrics fm = g.getFontMetrics();
        Rectangle clip = g.getClipBounds();
        try {

            int first = ta.getLineOfOffset(ta.viewToModel2D(new Point(0, clip.y)));
            for (int i = first; i < ta.getLineCount(); i++) {

                Rectangle r = ta.modelToView2D(ta.getLineStartOffset(i)).getBounds();
                if (r.y > clip.y + clip.height) break;
                String s = String.valueOf(i + 1);
                g.drawString(s, getWidth() - 8 - fm.stringWidth(s), r.y + fm.getAscent() + (r.height - fm.getHeight()) / 2);
            }
        } catch (BadLocationException ignored) { }
    }

    private void update() {
        
        revalidate();
        repaint();
    }

    @Override public void insertUpdate(DocumentEvent e) { update(); }
    @Override public void removeUpdate(DocumentEvent e) { update(); }
    @Override public void changedUpdate(DocumentEvent e) { update(); }
}
