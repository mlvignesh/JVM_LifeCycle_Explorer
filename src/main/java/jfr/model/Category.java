package jfr.model;

import java.awt.*;

public enum Category {

    LOADING("1 \u00b7 Loading", "Load", new Color(0x1E88E5)),
    LINKING("2 \u00b7 Linking", "Link", new Color(0x00897B)),
    INITIALIZATION("3 \u00b7 Initialization", "Init", new Color(0x43A047)),
    EXECUTION("4 \u00b7 Execution", "Exec", new Color(0xFB8C00)),
    MEMORY("Objects / Heap", "Memory", new Color(0xD81B60)),
    GC("Garbage Collection", "GC", new Color(0x6D4C41)),
    JIT("JIT Compiler", "JIT", new Color(0x8E24AA)),
    THREADS("Threads & Locks", "Threads", new Color(0x546E7A)),
    EXCEPTIONS("Exceptions", "Errors", new Color(0xD32F2F)),
    UNLOADING("5 \u00b7 Unloading", "Unload", new Color(0x757575)),
    SYSTEM("Tool / System", "Tool", new Color(0x9E9E9E));

    public final String label, shortLabel;
    public final Color color;

    Category(String label, String shortLabel, Color color) {
        
        this.label = label;
        this.shortLabel = shortLabel;
        this.color = color;
    }
}
