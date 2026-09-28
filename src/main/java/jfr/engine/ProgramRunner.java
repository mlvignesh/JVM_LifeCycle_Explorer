package jfr.engine;


import jfr.model.Category;
import jfr.model.Ev;
import jfr.model.Fr;
import jfr.model.UiUtils;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedClassLoader;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ProgramRunner {

    public interface Sink {

        void started(Instant runStart, String loaderName);
        void event(Ev ev);
        void console(String text);
        void heap(Instant t, long used, long committed);
        void compiled(Path dir, String mainClass, List<String> classes);
        void compileError(int line, String message);
        void liveStack(String thread, List<Fr> frames);
        void status(String s);
    }

    public static final class Config {

        public String code = "";
        public int periodMs = 10;
        public boolean forceGc = true;
        public int timeoutSec = 30;
    }

    private static final class Run {

        Ev ev;
        Instant last;
        long weight;
        String note = "";
    }

    private final Sink sink;
    private final Set<Long> owned = ConcurrentHashMap.newKeySet();
    private final Set<Long> ended = ConcurrentHashMap.newKeySet();
    private final Map<String, Run> runs = new HashMap<>();
    private volatile String loaderName = "user-loader";
    private volatile boolean stopRequested;
    private volatile ThreadGroup group;
    private volatile Thread userThread;
    private volatile long lastLiveStackMs;
    private int periodMs = 10;

    public ProgramRunner(Sink sink) { this.sink = sink; }

    public void stop() {

        stopRequested = true;
        Thread t = userThread;
        if (t != null) t.interrupt();
        ThreadGroup g = group;
        if (g != null) {
            try { g.interrupt(); } catch (Exception ignored) { }
        }
    }

    public void run(Config cfg) throws Exception {

        stopRequested = false;
        owned.clear();
        ended.clear();
        synchronized (this) { runs.clear(); }
        periodMs = Math.max(1, cfg.periodMs);
        loaderName = "user-loader-" + Integer.toString((int) (System.nanoTime() & 0xFFFFF), 36);

        String mainName = detectMainClass(cfg.code);
        Path dir = Files.createTempDirectory("jvm_visualizer_");
        Path src = dir.resolve(mainName + ".java");
        Files.writeString(src, cfg.code);

        sink.status("Compiling " + mainName + ".java ...");
        if (!compile(src, dir)) {

            sink.status("Compilation failed - see Console");
            return;
        }
        sink.compiled(dir, mainName, listClasses(dir, mainName));

        PrintStream oldOut = System.out, oldErr = System.err;
        PrintStream consoleStream = new PrintStream(new ConsoleStream(sink), true, StandardCharsets.UTF_8);
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {

            Thread t = new Thread(r, "heap-sampler");
            t.setDaemon(true);
            return t;
        });

        try (RecordingStream rs = new RecordingStream()) {

            configure(rs);
            rs.startAsync();
            Thread.sleep(150);

            sink.started(Instant.now(), loaderName);
            sink.status("Running " + mainName + " ...");
            sampler.scheduleAtFixedRate(() -> {
                Runtime rt = Runtime.getRuntime();
                long total = rt.totalMemory();
                sink.heap(Instant.now(), total - rt.freeMemory(), total);
            }, 0, 20, TimeUnit.MILLISECONDS);

            System.setOut(consoleStream);
            System.setErr(consoleStream);
            try {
                runUser(dir, mainName, cfg);
            } finally {
                System.setOut(oldOut);
                System.setErr(oldErr);
            }

            if (cfg.forceGc && !stopRequested) {

                Instant t = Instant.now();
                milestone(Category.UNLOADING, "step-gc", "STEP 5 \u00b7 UNLOAD / GC", "System.gc()", t, 0,
                        "Program finished and its class loader is now unreachable. Requesting a garbage collection - if it succeeds you will see the classes unloaded.");
                System.gc();
                Thread.sleep(300);
            }
            Thread.sleep(350);
            sampler.shutdownNow();
            rs.stop();
            rs.awaitTermination(Duration.ofSeconds(3));
            flushRuns();
        } finally {

            sampler.shutdownNow();
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        sink.status("Done");
    }

    private boolean compile(Path src, Path dir) throws IOException {

        JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {

            sink.console("ERROR: no Java compiler found. Run this tool with a JDK (not a JRE).\n");
            return false;
        }
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        boolean ok;
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, Locale.ENGLISH, StandardCharsets.UTF_8)) {
            
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(src.toFile());
            ok = compiler.getTask(null, fm, diags, List.of("-g", "-d", dir.toString()), null, units).call();
        }
        for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
            
            int line = (int) d.getLineNumber();
            String msg = d.getMessage(Locale.ENGLISH);
            sink.console(d.getKind() + (line > 0 ? " (line " + line + "): " : ": ") + msg + "\n");
            if (d.getKind() == Diagnostic.Kind.ERROR && line > 0) sink.compileError(line, msg);
        }
        if (ok) 
            sink.console("Compilation OK\n----------------------------------------\n");
        return ok;
    }

    public static String detectMainClass(String code) {

        Matcher m = Pattern.compile(
                "(?m)^\\s*public\\s+(?:final\\s+|abstract\\s+)*(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
                .matcher(code);
        if (m.find()) 
            return m.group(1);
        m = Pattern.compile("\\bclass\\s+([A-Za-z_$][A-Za-z0-9_$]*)").matcher(code);
        return m.find() ? m.group(1) : "Main";
    }

    private static List<String> listClasses(Path dir, String main) throws IOException {
        
        try (Stream<Path> s = Files.list(dir)) {

            List<String> names = s.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".class"))
                    .map(n -> n.substring(0, n.length() - 6))
                    .sorted().collect(Collectors.toCollection(ArrayList::new));
            if (names.remove(main)) names.add(0, main);
            return names;
        }
    }

    private void runUser(Path dir, String mainName, Config cfg) throws Exception {
        
        ThreadGroup g = new ThreadGroup("user-group");
        group = g;
        long deadline = System.currentTimeMillis() + cfg.timeoutSec * 1000L;
        URLClassLoader loader = new URLClassLoader(loaderName, new URL[]{dir.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        Thread t = new Thread(g, () -> executePhases(loader, mainName), "main");
        owned.add(t.getId());
        userThread = t;
        t.start();
        t.join(Math.max(1, deadline - System.currentTimeMillis()));
        if (t.isAlive()) {

            System.err.println("[tool] Timed out after " + cfg.timeoutSec + " s - interrupting your program (it may keep running in the background).");
            stop();
        }
        while (!stopRequested && System.currentTimeMillis() < deadline && nonDaemonAlive(g)) 
            Thread.sleep(40);
        if (nonDaemonAlive(g)) {

            System.err.println("[tool] Some of your threads are still running - interrupting them.");
            g.interrupt();
        }
        userThread = null;
        group = null;
        try {
             
            loader.close(); 
        } catch (IOException ignored) { }
    }

    private static boolean nonDaemonAlive(ThreadGroup g) {

        Thread[] ts = new Thread[g.activeCount() + 16];
        int n = g.enumerate(ts, true);
        for (int i = 0; i < n; i++) if (ts[i].isAlive() && !ts[i].isDaemon()) return true;
        return false;
    }

    private void executePhases(ClassLoader loader, String mainName) {

        Class<?> cls;
        Instant t = Instant.now();
        long n = System.nanoTime();
        try {

            cls = Class.forName(mainName, false, loader);
        } catch (Throwable ex) {

            fail("STEP 1 \u00b7 LOAD failed", ex);
            return;
        }
        milestone(Category.LOADING, "step-load", "STEP 1 \u00b7 LOAD", mainName, t, System.nanoTime() - n,
                "Class.forName(\"" + mainName + "\", false, loader) - bytecode found, Class object created. Static initializers have NOT run yet.");

        t = Instant.now();
        n = System.nanoTime();
        try {

            cls.getDeclaredMethods();
        } catch (Throwable ex) {

            fail("STEP 2 \u00b7 LINK failed", ex);
            return;
        }
        milestone(Category.LINKING, "step-link", "STEP 2 \u00b7 LINK", mainName, t, System.nanoTime() - n,
                "Bytecode verified, static fields prepared with default values (0 / false / null).");

        t = Instant.now();
        n = System.nanoTime();
        try {

            Class.forName(mainName, true, loader);
        } catch (Throwable ex) {
            
            fail("STEP 3 \u00b7 INITIALIZE failed", ex);
            return;
        }
        milestone(Category.INITIALIZATION, "step-init", "STEP 3 \u00b7 INITIALIZE", mainName + ".<clinit>()", t,
                System.nanoTime() - n, "Static fields assigned and static { } blocks executed, in source order.");

        Method main;
        try {

            main = cls.getDeclaredMethod("main", String[].class);
            if (!Modifier.isStatic(main.getModifiers())) throw new NoSuchMethodException("main is not static");
        } catch (NoSuchMethodException ex) {

            System.err.println("No 'public static void main(String[] args)' found in " + mainName);
            return;
        }

        t = Instant.now();
        n = System.nanoTime();
        milestone(Category.EXECUTION, "step-run", "STEP 4 \u00b7 RUN main()", mainName + ".main(String[])", t, 0,
                "A stack frame for main() was pushed onto this thread's stack.");
        String result = "main() returned normally";
        boolean ok = true;
        try {

            main.setAccessible(true);
            main.invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException ex) {

            Throwable c = ex.getCause() != null ? ex.getCause() : ex;
            System.err.print("Exception in thread \"main\" ");
            c.printStackTrace();
            ok = false;
            result = "main() terminated by " + c;
        } catch (Throwable ex) {

            ex.printStackTrace();
            ok = false;
            result = "could not invoke main(): " + ex;
        }
        milestone(Category.EXECUTION, "step-end", ok ? "STEP 4 \u00b7 main() RETURNED" : "STEP 4 \u00b7 main() FAILED",
                mainName + ".main(String[])", t, System.nanoTime() - n, result);
    }

    private void fail(String what, Throwable ex) {

        ex.printStackTrace();
        milestone(Category.EXCEPTIONS, "exception", what, ex.toString(), Instant.now(), 0,
                "The JVM refused to continue with this class. See the Console for the stack trace.");
    }

    private void milestone(Category c, String key, String type, String target, Instant t, long durNs, String details) {
        
        Ev e = new Ev(c, key, type);
        e.milestone = true;
        e.start = t;
        e.durNs = durNs;
        e.target = target;
        e.details = details;
        e.thread = threadLabel(Thread.currentThread());
        sink.event(e);
    }

    private void configure(RecordingStream rs) {
        
        rs.enable("jdk.ClassLoad").withThreshold(Duration.ZERO).withStackTrace();
        rs.enable("jdk.ClassDefine").withStackTrace();
        rs.enable("jdk.ClassUnload");
        rs.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(periodMs));
        rs.enable("jdk.ObjectAllocationSample").with("throttle", "20000/s").withStackTrace();
        rs.enable("jdk.ThreadStart").withStackTrace();
        rs.enable("jdk.ThreadEnd");
        rs.enable("jdk.ThreadSleep").withThreshold(Duration.ZERO).withStackTrace();
        rs.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ofMillis(1)).withStackTrace();
        rs.enable("jdk.JavaMonitorWait").withThreshold(Duration.ofMillis(1)).withStackTrace();
        rs.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1)).withStackTrace();
        rs.enable("jdk.JavaExceptionThrow").withStackTrace();
        rs.enable("jdk.JavaErrorThrow").withStackTrace();
        rs.enable("jdk.GarbageCollection");
        rs.enable("jdk.GCHeapSummary");
        rs.enable("jdk.Compilation").withThreshold(Duration.ZERO);
        rs.enable("jdk.Deoptimization").withStackTrace();

        // ---- class lifecycle
        rs.onEvent("jdk.ClassLoad", e -> {
            RecordedClass c = e.getClass("loadedClass");
            if (c == null) return;
            RecordedClassLoader cl = loaderOf(e, "definingClassLoader");
            boolean user = isUserLoader(cl);
            if (!user && !hasUserFrame(e)) return;
            Ev ev = mk(e, Category.LOADING, "load", "Class Load");
            ev.target = c.getName();
            ev.extra = loaderLabel(cl);
            ev.jdk = !user;
            ev.details = user
                    ? "Defined by loader \"" + ev.extra + "\" (asked parents first: platform -> bootstrap)"
                    : "JDK class first needed by your code. Defined by: " + ev.extra;
            sink.event(ev);
        });
        rs.onEvent("jdk.ClassDefine", e -> {
            RecordedClass c = e.getClass("definedClass");
            if (c == null) return;
            RecordedClassLoader cl = loaderOf(e, "definingClassLoader");
            boolean user = isUserLoader(cl);
            if (!user && !hasUserFrame(e)) return;
            Ev ev = mk(e, Category.LOADING, "define", "Class Define");
            ev.target = c.getName();
            ev.extra = loaderLabel(cl);
            ev.jdk = !user;
            ev.details = "Bytecode parsed; Class object created in Metaspace by loader \"" + ev.extra + "\"";
            sink.event(ev);
        });
        rs.onEvent("jdk.ClassUnload", e -> {
            RecordedClass c = e.getClass("unloadedClass");
            if (c == null) return;
            RecordedClassLoader cl = loaderOf(e, "definingClassLoader");
            boolean user = isUserLoader(cl);
            if (!user) return;
            Ev ev = mk(e, Category.UNLOADING, "unload", "Class Unload");
            ev.target = c.getName();
            ev.extra = loaderLabel(cl);
            ev.jdk = !user;
            ev.details = "Metadata freed from Metaspace - loader \"" + ev.extra + "\" was garbage collected";
            sink.event(ev);
        });

        rs.onEvent("jdk.ExecutionSample", this::onSample);

        rs.onEvent("jdk.ObjectAllocationSample", e -> {
            List<Fr> fr = frames(e.getStackTrace());
            if (firstUser(fr) < 0) return;
            RecordedClass oc = e.getClass("objectClass");
            String cn = oc == null ? "?" : oc.getName();
            long w = num(e, "weight");
            Fr uf = fr.get(firstUser(fr));
            String th = threadName(e);
            coalesce("alloc|" + th + "|" + cn, e.getStartTime(), 100, () -> {
                Ev ev = mk(e, Category.MEMORY, "alloc", "Object Allocation (sampled)");
                ev.target = cn;
                return ev;
            }, Math.max(w, 0), "Last seen in " + uf.simple() + "." + uf.method() + "() line " + uf.line() + ".");
        });

        rs.onEvent("jdk.GarbageCollection", e -> {
            Ev ev = mk(e, Category.GC, "gc", "GC Cycle");
            ev.thread = "JVM (GC)";
            ev.target = str(e, "name") + " - " + str(e, "cause");
            ev.bytes = Math.max(0, num(e, "sumOfPauses"));
            ev.details = "Stop-the-world pauses: total " + UiUtils.fmtDur(num(e, "sumOfPauses")) + ", longest " + UiUtils.fmtDur(num(e, "longestPause"))
                    + "  (gcId " + num(e, "gcId") + ", JVM-wide)";
            sink.event(ev);
        });
        rs.onEvent("jdk.GCHeapSummary", e -> {
            long used = num(e, "heapUsed");
            long committed = -1;
            Object hs = val(e, "heapSpace");
            if (hs instanceof RecordedObject ro) committed = num(ro, "committedSize");
            Ev ev = mk(e, Category.GC, "heap", "Heap Summary");
            ev.thread = "JVM (GC)";
            ev.target = str(e, "when");
            ev.details = "Heap in use " + UiUtils.fmtBytes(used) + ", committed " + UiUtils.fmtBytes(committed) + "  (gcId " + num(e, "gcId") + ")";
            sink.event(ev);
        });

        rs.onEvent("jdk.Compilation", e -> {
            Object mo = val(e, "method");
            if (!(mo instanceof RecordedMethod m)) return;
            boolean user = isUserClass(m.getType());
            Ev ev = mk(e, Category.JIT, "jit", "JIT Compile");
            ev.jdk = !user;
            ev.target = (m.getType() == null ? "?" : UiUtils.simpleName(m.getType().getName())) + "." + m.getName() + "()";
            ev.extra = tier(num(e, "compileLevel"));
            boolean osr = Boolean.TRUE.equals(val(e, "isOsr"));
            ev.details = ev.extra + (osr ? " \u00b7 OSR (swapped in while the loop was running)" : "")
                    + " \u00b7 " + num(e, "codeSize") + " bytes of machine code \u00b7 " + num(e, "inlinedBytes") + " bytes of bytecode inlined";
            sink.event(ev);
        });
        rs.onEvent("jdk.Deoptimization", e -> {
            Object mo = val(e, "method");
            if (!(mo instanceof RecordedMethod m)) return;
            boolean user = isUserClass(m.getType());
            Ev ev = mk(e, Category.JIT, "deopt", "Deoptimization");
            ev.jdk = !user;
            ev.target = (m.getType() == null ? "?" : UiUtils.simpleName(m.getType().getName())) + "." + m.getName() + "()";
            ev.details = "Reason: " + str(e, "reason") + " \u00b7 action: " + str(e, "action") + " \u00b7 line " + num(e, "lineNumber");
            sink.event(ev);
        });

        rs.onEvent("jdk.ThreadStart", e -> {
            Object to = val(e, "thread"), po = val(e, "parentThread");
            if (!(to instanceof RecordedThread t)) return;
            RecordedThread p = po instanceof RecordedThread pp ? pp : null;
            boolean fromUser = p != null && owned.contains(p.getJavaThreadId());
            if (!fromUser && !owned.contains(t.getJavaThreadId())) return;
            owned.add(t.getJavaThreadId());
            Ev ev = mk(e, Category.THREADS, "thread-start", "Thread Start");
            ev.target = threadLabel(t);
            ev.details = p == null ? "Thread started" : "Started by " + threadLabel(p);
            sink.event(ev);
        });
        rs.onEvent("jdk.ThreadEnd", e -> {
            Object to = val(e, "thread");
            if (!(to instanceof RecordedThread t) || !owned.contains(t.getJavaThreadId())) return;
            if (!ended.add(t.getJavaThreadId())) return;
            Ev ev = mk(e, Category.THREADS, "thread-end", "Thread End");
            ev.target = threadLabel(t);
            ev.details = "Thread finished";
            sink.event(ev);
        });
        rs.onEvent("jdk.ThreadSleep", e -> {
            if (!ownedOrUser(e)) return;
            Ev ev = mk(e, Category.THREADS, "sleep", "Thread.sleep");
            ev.target = "sleep(" + String.format("%.0f", num(e, "time") / 1e6) + " ms)";
            ev.details = "Thread was TIMED_WAITING - no CPU used";
            sink.event(ev);
        });
        rs.onEvent("jdk.JavaMonitorEnter", e -> {
            if (!ownedOrUser(e)) return;
            Ev ev = mk(e, Category.THREADS, "monitor-enter", "Lock Contention (BLOCKED)");
            RecordedClass mc = e.getClass("monitorClass");
            Object po = val(e, "previousOwner");
            ev.target = "synchronized on " + (mc == null ? "?" : mc.getName());
            ev.details = "Waited " + UiUtils.fmtDur(e.getDuration().toNanos()) + " for the lock" + (po instanceof RecordedThread p ? ", held by " + threadLabel(p) : "");
            sink.event(ev);
        });
        rs.onEvent("jdk.JavaMonitorWait", e -> {
            if (!ownedOrUser(e)) return;
            Ev ev = mk(e, Category.THREADS, "monitor-wait", "Object.wait()");
            RecordedClass mc = e.getClass("monitorClass");
            ev.target = "wait() on " + (mc == null ? "?" : mc.getName());
            ev.details = "Waited " + UiUtils.fmtDur(e.getDuration().toNanos()) + (Boolean.TRUE.equals(val(e, "timedOut")) ? " (timed out)" : "");
            sink.event(ev);
        });
        rs.onEvent("jdk.ThreadPark", e -> {
            if (!ownedOrUser(e)) return;
            Ev ev = mk(e, Category.THREADS, "park", "Thread Parked");
            RecordedClass pc = e.getClass("parkedClass");
            ev.target = "LockSupport.park(" + (pc == null ? "?" : UiUtils.simpleName(pc.getName())) + ")";
            ev.details = "Parked for " + UiUtils.fmtDur(e.getDuration().toNanos());
            sink.event(ev);
        });

        Consumer<RecordedEvent> thrown = e -> {
            
            List<Fr> fr = frames(e.getStackTrace());
            if (firstUser(fr) < 0) return;
            RecordedClass tc = e.getClass("thrownClass");
            boolean err = e.getEventType().getName().endsWith("ErrorThrow");
            Ev ev = mk(e, Category.EXCEPTIONS, "exception", err ? "Error Thrown" : "Exception Thrown");
            ev.target = tc == null ? "?" : tc.getName();
            String msg = str(e, "message");
            ev.details = msg.isEmpty() ? "(no message)" : "message: " + msg;
            boolean delegation = "java.lang.ClassNotFoundException".equals(ev.target)
                    && fr.stream().limit(8).anyMatch(f -> f.cls().contains("ClassLoader"));
            if (delegation) {
                ev.jdk = true;
                ev.details = "Normal class-loader delegation: a parent loader did not have \"" + msg + "\", so the search moved on. The JDK catches this internally.";
            }
            sink.event(ev);
        };
        rs.onEvent("jdk.JavaExceptionThrow", thrown);
        rs.onEvent("jdk.JavaErrorThrow", thrown);
    }

    private void onSample(RecordedEvent e) {
        
        List<Fr> fr = frames(e.getStackTrace());
        int ui = firstUser(fr);
        if (ui < 0) return;
        Fr uf = fr.get(ui), top = fr.get(0);
        String th = threadName(e);

        long now = System.currentTimeMillis();
        if (now - lastLiveStackMs > 100) {
            lastLiveStackMs = now;
            sink.liveStack(th, fr);
        }

        String key, type;
        Category cat = Category.EXECUTION;
        if (uf.method().equals("<clinit>")) { key = "clinit"; type = "Static Initializer (sampled)"; cat = Category.INITIALIZATION; }
        else if (uf.method().equals("<init>")) { key = "init"; type = "Constructor (sampled)"; }
        else { key = "run"; type = "Method Running (sampled)"; }

        String target = uf.cls() + "." + uf.method() + "()";
        Category fc = cat;
        String fk = key, ft = type;
        String note = "Latest sample at line " + uf.line() + (top != uf ? "; top of stack is in " + top.full() + " [" + top.kind() + "]" : " [" + top.kind() + "]");
        coalesce(key + "|" + th + "|" + target, e.getStartTime(), Math.max(120, periodMs * 6L), () -> {
            Ev ev = mk(e, fc, fk, ft);
            ev.target = target;
            return ev;
        }, 0, note);
    }

    private static long ms(Instant a, Instant b) { 
        
        return Duration.between(a, b).toMillis(); 
    }

    private synchronized void coalesce(String key, Instant t, long windowMs, Supplier<Ev> factory, long weight, String note) {
        
        Run r = runs.get(key);
        if (r != null && (ms(r.last, t) > windowMs || ms(r.ev.start, t) > 1000)) {
            
            close(r);
            runs.remove(key);
            r = null;
        }
        if (r == null) {
            
            r = new Run();
            r.ev = factory.get();
            r.ev.start = t;
            r.ev.count = 0;
            r.last = t;
            runs.put(key, r);
        }
        r.ev.count++;
        r.weight += weight;
        r.last = t;
        r.note = note;
        for (Iterator<Run> it = runs.values().iterator(); it.hasNext(); ) {
            
            Run o = it.next();
            if (o != r && ms(o.last, t) > windowMs) {
                
                close(o);
                it.remove();
            }
        }
    }

    private synchronized void flushRuns() {
        
        for (Run r : runs.values()) close(r);
        runs.clear();
    }

    private void close(Run r) {
        
        Ev ev = r.ev;
        long span = Duration.between(ev.start, r.last).toNanos();
        if (ev.key.equals("alloc")) {
            
            ev.durNs = span;
            ev.bytes = r.weight;
            ev.details = ev.count + " sampled allocation(s), \u2248 " + UiUtils.fmtBytes(r.weight) + " in total. " + r.note;
        } else {
            
            ev.durNs = span + periodMs * 1_000_000L;
            ev.details = ev.count + " sample(s) \u2248 " + UiUtils.fmtDur(ev.durNs) + ". " + r.note;
        }
        sink.event(ev);
    }

    private Ev mk(RecordedEvent e, Category c, String key, String type) {
        
        Ev ev = new Ev(c, key, type);
        ev.start = e.getStartTime();
        ev.durNs = e.getDuration().toNanos();
        ev.jfrEvent = e.getEventType().getName();
        ev.thread = threadName(e);
        ev.stack = frames(e.getStackTrace());
        int ui = firstUser(ev.stack);
        ev.userLine = ui >= 0 ? ev.stack.get(ui).line() : -1;
        ev.raw = rawFields(e);
        return ev;
    }

    private List<Fr> frames(RecordedStackTrace st) {
        
        if (st == null) 
            return List.of();
        List<Fr> out = new ArrayList<>();
        for (RecordedFrame f : st.getFrames()) {
            
            RecordedMethod m = f.getMethod();
            RecordedClass c = m.getType();
            out.add(new Fr(c == null ? "?" : c.getName(), m.getName(), f.getLineNumber(), f.getBytecodeIndex(),
                    f.getType() == null ? "" : f.getType(), isUserClass(c)));
            if (out.size() >= 80) break;
        }
        return out;
    }

    private static int firstUser(List<Fr> fr) {
        
        for (int i = 0; i < fr.size(); i++) if (fr.get(i).user()) return i;
        return -1;
    }

    private boolean isUserLoader(RecordedClassLoader cl) {
        
        return cl != null && loaderName.equals(cl.getName());
    }

    private boolean isUserClass(RecordedClass c) {
        
        return c != null && isUserLoader(c.getClassLoader());
    }

    private static String loaderLabel(RecordedClassLoader cl) {
        
        if (cl == null) return "bootstrap";
        if (cl.getName() != null) return cl.getName();
        return cl.getType() != null ? cl.getType().getName() : "?";
    }

    private static RecordedClassLoader loaderOf(RecordedEvent e, String field) {
        
        return val(e, field) instanceof RecordedClassLoader cl ? cl : null;
    }

    private boolean hasUserFrame(RecordedEvent e) {
        
        return firstUser(frames(e.getStackTrace())) >= 0;
    }

    private boolean ownedOrUser(RecordedEvent e) {
        
        RecordedThread t = threadOf(e);
        return (t != null && owned.contains(t.getJavaThreadId())) || firstUser(frames(e.getStackTrace())) >= 0;
    }

    private static RecordedThread threadOf(RecordedEvent e) {
        
        try {
            
            if (e.hasField("sampledThread")) 
                return e.getThread("sampledThread");
            return e.getThread();
        } catch (Exception ex) {
            
            return null;
        }
    }

    private static String threadName(RecordedEvent e) {
        
        RecordedThread t = threadOf(e);
        return t == null ? "JVM" : threadLabel(t);
    }

    public static String threadLabel(RecordedThread t) {
        
        String n = t.getJavaName() != null ? t.getJavaName() : t.getOSName();
        return n + " (ID: " + t.getJavaThreadId() + ")";
    }

    public static String threadLabel(Thread t) {
        
        return t.getName() + " (ID: " + t.getId() + ")";
    }

    private static Object val(RecordedObject o, String f) {
        
        try {
            
            if (!o.hasField(f)) return null;
            Object v = o.getValue(f);
            return v;
        } catch (Exception ex) {
            
            return null;
        }
    }

    private static long num(RecordedObject o, String f) {
        
        Object v = val(o, f);
        if (v instanceof Number n) 
            return n.longValue();
        if (v instanceof Duration d) 
            return d.toNanos();
        return -1;
    }

    private static String str(RecordedObject o, String f) {
        
        Object v = val(o, f);
        return v == null ? "" : String.valueOf(v);
    }

    private static String tier(long level) {
        
        return switch ((int) level) {
            case 0 -> "Interpreter";
            case 1 -> "C1 (tier 1, no profiling)";
            case 2 -> "C1 (tier 2, light profiling)";
            case 3 -> "C1 (tier 3, full profiling)";
            case 4 -> "C2 (tier 4, fully optimized)";
            default -> "tier " + level;
        };
    }

    private static String rawFields(RecordedEvent e) {
        
        StringBuilder sb = new StringBuilder();
        for (ValueDescriptor f : e.getFields()) {
            
            String n = f.getName();
            if (n.equals("stackTrace")) continue;
            Object v = val(e, n);
            String s = String.valueOf(v).replace('\n', ' ').replaceAll("\\s+", " ");
            if (s.length() > 240) s = s.substring(0, 240) + "\u2026";
            sb.append(String.format("%-20s = %s", n, s));
            if (f.getLabel() != null) sb.append("    // ").append(f.getLabel());
            sb.append('\n');
        }
        return sb.toString();
    }

    public static final class ConsoleStream extends OutputStream {
        
        private final ProgramRunner.Sink sink;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        public ConsoleStream(ProgramRunner.Sink sink) { this.sink = sink; }

        @Override public synchronized void write(int b) { buf.write(b); }

        @Override public synchronized void write(byte[] b, int off, int len) {
            
            buf.write(b, off, len);
            flushIfNewline();
        }

        @Override public synchronized void flush() {
            
            if (buf.size() == 0) 
                return;
            String text = buf.toString(StandardCharsets.UTF_8);
            buf.reset();
            sink.console(text);
        }

        private void flushIfNewline() {
            
            byte[] bytes = buf.toByteArray();
            for (int i = bytes.length - 1; i >= 0; i--) {
                
                if (bytes[i] == '\n') {
                    
                    String text = new String(bytes, 0, i + 1, StandardCharsets.UTF_8);
                    buf.reset();
                    if (i + 1 < bytes.length) buf.write(bytes, i + 1, bytes.length - (i + 1));
                    sink.console(text);
                    return;
                }
            }
        }
    }
}
