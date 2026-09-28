package jfr.model;

import java.time.Instant;
import java.util.List;

/** One row in the event log. */
public final class Ev {

    public int seq;
    public Instant start = Instant.now();
    public long durNs;
    public Category cat;
    public String key;            // drives the explanation text
    public String type;           // short human title
    public String thread = "";
    public String target = "";
    public String details = "";
    public String extra = "";     // loader name (class events) / tier (JIT)
    public String jfrEvent = "";  // original JFR event name, "" for tool-generated milestones
    public String raw = "";       // raw JFR fields, for the curious
    public List<Fr> stack = List.of();
    public boolean jdk;           // JDK-internal noise (hidden by default)
    public boolean milestone;     // tool-driven lifecycle step
    public int count = 1;         // >1 when several samples were merged
    public long bytes;
    public int userLine = -1;     // line in the user's source, for editor highlighting

    public Ev(Category cat, String key, String type) {
        
        this.cat = cat;
        this.key = key;
        this.type = type;
    }
}
