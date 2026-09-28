package jfr.model;

/** One stack frame. {@code kind} is JFR's view: Interpreted / JIT compiled / Inlined / Native. */
public record Fr(String cls, String method, int line, int bci, String kind, boolean user) {

    public String simple() {

        int i = cls.lastIndexOf('.');
        return i >= 0 ? cls.substring(i + 1) : cls;
    }

    public String full() {
        
        return cls + "." + method + "()";
    }
}
