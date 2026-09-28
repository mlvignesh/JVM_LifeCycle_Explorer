package jfr.model;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Story {

    public boolean ok;
    public String main = "Main";
    public long loadNs, linkNs, initNs, mainNs;
    public double initEndMs;
    public boolean ranMain, failed, forcedGc;
    public final Map<String, Double> classes = new LinkedHashMap<>();   // your classes -> first load time (ms)
    public final List<String> unloaded = new ArrayList<>();
    public int jdkClasses;
    public long allocBytes;
    public final Map<String, long[]> allocs = new HashMap<>();          // class -> {samples, bytes}
    public int jit, deopts, exceptions, threads, gcs;
    public long gcPauseNs, sleepNs;
    public int sleepCount;
    public List<Fr> stack = List.of();

    public static final class Chip {

        private final String text;
        private final Color color;
        private final String tag;
        private final boolean faded;
        private final boolean struck;

        public Chip(String text, Color color, String tag, boolean faded, boolean struck) {

            this.text = text;
            this.color = color;
            this.tag = tag;
            this.faded = faded;
            this.struck = struck;
        }

        public Chip(String text, Color color, String tag) {

            this(text, color, tag, false, false);
        }

        public String text() { return text; }
        public Color color() { return color; }
        public String tag() { return tag; }
        public boolean faded() { return faded; }
        public boolean struck() { return struck; }
    }

    public static final class Pic {
        
        public final List<Chip> meta = new ArrayList<>();
        public final List<Chip> stack = new ArrayList<>();     // bottom of the stack first
        public final List<Chip> heap = new ArrayList<>();
        public String metaHint = "Class blueprints will appear here.";
        public String stackHint = "Nothing is running yet.";
        public String heapHint = "Objects will appear here.";
        public String caption = "";
        public int glow = -1;                                   // 0 = metaspace, 1 = stack, 2 = heap
    }
}
