package jfr.model;

import javax.swing.table.*;
import java.time.*;
import java.util.*;

public final class EventTableModel extends AbstractTableModel {

    public static final String[] COLS = {"#", "Time", "Duration", "Thread", "Phase", "Event", "Target", "Details"};
    private final List<Ev> rows = new ArrayList<>();
    public Instant origin = Instant.now();

    public List<Ev> all() { return rows; }
    public Ev get(int i) { return rows.get(i); }
    public double relMs(Ev e) { return Duration.between(origin, e.start).toNanos() / 1e6; }

    public void clear() {

        rows.clear();
        fireTableDataChanged();
    }

    public void append(List<Ev> batch) {

        int first = rows.size();
        rows.addAll(batch);
        fireTableRowsInserted(first, rows.size() - 1);
    }

    public void sortByTime() {

        rows.sort(Comparator.comparing((Ev e) -> e.start).thenComparingInt(e -> e.seq));
        for (int i = 0; i < rows.size(); i++) rows.get(i).seq = i + 1;
        fireTableDataChanged();
    }

    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return COLS.length; }
    @Override public String getColumnName(int c) { return COLS[c]; }

    @Override public Class<?> getColumnClass(int c) {

        return switch (c) {
            case 0 -> Integer.class;
            case 1 -> Double.class;
            case 2 -> Long.class;
            case 4 -> Category.class;
            default -> String.class;
        };
    }

    @Override public Object getValueAt(int r, int c) {
        
        Ev e = rows.get(r);
        return switch (c) {
            case 0 -> e.seq;
            case 1 -> relMs(e);
            case 2 -> e.durNs;
            case 3 -> e.thread;
            case 4 -> e.cat;
            case 5 -> e.type;
            case 6 -> e.target;
            default -> e.details;
        };
    }
}
