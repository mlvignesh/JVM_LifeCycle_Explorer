package jfr.content;

import jfr.model.Ev;

public final class Explanations {

    public static String explain(Ev ev) {

        String base = switch (ev.key) {

            case "step-load" -> """
                <b>Loading</b> is the first stage of a class's life. A <i>ClassLoader</i> finds the <code>.class</code> file
                (bytecode), reads it and the JVM builds a <code>Class</code> object describing it inside <b>Metaspace</b>
                (native memory - not the Java heap).<br><br>
                Loading is <b>lazy</b>: a class is only loaded when your code first needs it.
                The tool calls <code>Class.forName(name, <b>false</b>, loader)</code> - the <code>false</code> means
                "load it, but do <u>not</u> run its static initializers yet".""";
            case "step-link" -> """
                <b>Linking (Step 2)</b> bridges the raw bytes to executable JVM structures across 3 sub-phases:<br>
                &bull; <b>1. Verification</b> - JVM verifier ensures bytecode validity, type-safety, stack alignment, and bounds.<br>
                &bull; <b>2. Preparation</b> - Memory is reserved for <code>static</code> fields and initialized to <i>zero/null defaults</i> (NOT your assigned values yet!).<br>
                &bull; <b>3. Resolution</b> - Symbolic references in the Constant Pool (e.g. method/field names) are resolved to direct memory pointers.<br><br>
                <i>Note for beginners:</i> JFR has no native <code>jdk.ClassLink</code> event, so this tool programmatically triggers and benchmarks HotSpot linking via reflection (<code>cls.getDeclaredMethods()</code>).""";
            case "step-init" -> """
                <b>Initialization (Step 3)</b> executes the class's synthetic <code>&lt;clinit&gt;</code> (class initializer) method:<br>
                &bull; Static field expressions (e.g. <code>static int x = 42;</code>) and <code>static { ... }</code> blocks run <b>in source order</b>.<br>
                &bull; Runs <b>exactly once</b>, lazily, on first active use (<code>new</code>, static method call, or non-constant static field read).<br>
                &bull; Check the Console: static initializers print <u>before</u> <code>main()</code> even begins!""";
            case "step-run" -> """
                The JVM creates a <b>stack frame</b> for <code>main()</code> on this thread's <b>stack</b> and starts interpreting bytecode.
                Every method call pushes a new frame; every return pops one. Local variables and primitive values live in frames;
                objects live on the <b>heap</b> (frames only hold references to them).""";
            case "step-end" -> """
                <code>main()</code> has returned (or thrown). The JVM keeps running until all non-daemon threads finish.
                The rows between STEP 4 markers show everything your program triggered: more class loading, object allocation,
                sleeping, JIT compilation, garbage collection...""";
            case "step-gc" -> """
                The tool dropped every reference to the program's class loader and called <code>System.gc()</code>.
                A class can be <b>unloaded</b> only when its <i>ClassLoader</i> becomes unreachable - so classes loaded by the
                bootstrap/platform/app loaders essentially live forever, while our isolated loader's classes can go away.""";
            case "load" -> """
                A <b>ClassLoader was asked for a class.</b> Class loaders use <i>parent delegation</i>: they ask their parent first
                (isolated loader &rarr; platform loader &rarr; bootstrap loader), and only load the class themselves if no parent has it.
                That's why nobody can replace <code>java.lang.String</code> with their own version.<br><br>
                The <b>Loader</b> in the details tells you which loader ended up defining the class.""";
            case "define" -> """
                <b>defineClass</b>: the raw bytes of the <code>.class</code> file were parsed and turned into a <code>Class</code> object in Metaspace.
                Load asks "who has this class?", Define is "here are its bytes, create it". If you see Define for a class of yours,
                that's its birth moment.""";
            case "unload" -> """
                A class was <b>unloaded</b> - its metadata was freed from Metaspace because its class loader became garbage.
                This is stage 5 of the lifecycle and it only happens during garbage collection.""";
            case "clinit" -> """
                A profiler sample caught the thread inside a <b>static initializer</b> (<code>&lt;clinit&gt;</code>).
                JFR samples the stack every few milliseconds, so very short initializers can slip between samples -
                the Console output and the STEP 3 marker are always exact. Lower the sampling period to catch more.""";
            case "init" -> """
                A profiler sample caught the thread inside a <b>constructor</b> (<code>&lt;init&gt;</code>). Constructors run
                right after the JVM allocates memory for <code>new</code>: fields get default values, then the super-constructor runs,
                then instance initializers, then the constructor body. Sampling is statistical - fast constructors may not appear.""";
            case "run" -> """
                A <b>profiler sample</b>: JFR periodically pauses each thread and records its call stack. Consecutive samples in the
                same method are merged into one row. Long rows = code that consumed lots of time.<br><br>
                Select the row and open <b>Call Stack</b> below to see the frames and whether they were <i>Interpreted</i> or
                <i>JIT compiled</i>.""";
            case "alloc" -> """
                <b>Objects are being created on the heap.</b> Every <code>new</code> allocates memory in the Java heap
                (usually in a fast thread-local buffer called a <i>TLAB</i>). JFR records a <i>sample</i> of allocations, not each one, so
                "\u2248 bytes" is an estimate that scales up each sample by how much it represents.<br><br>
                The Java heap is where garbage collection happens. See the <b>Heap &amp; GC</b> tab.""";
            case "gc" -> """
                A <b>garbage collection cycle</b>. The GC finds objects that are no longer reachable from any thread stack or static field
                and reclaims their memory. Most collectors briefly pause your program (<i>stop-the-world</i>) for part of the work.
                Young collections are frequent and cheap; full collections are rare and expensive.<br><br>
                GC is JVM-wide: this can include garbage created by the tool's own window as well as by your program.""";
            case "heap" -> """
                A <b>heap snapshot</b> taken just before or after a collection. Compare "Before GC" and "After GC":
                the difference is the memory that was reclaimed. If "After" stays high, your program is holding on to objects.""";
            case "jit" -> """
                The <b>JIT compiler</b> turned hot bytecode into native machine code. The JVM starts by <i>interpreting</i> bytecode
                (slow to run, instant to start). It counts how often methods and loops execute, and compiles the hot ones:<br>
                &bull; <b>C1</b> (tiers 1-3): quick compile, modest speed-up<br>
                &bull; <b>C2</b> (tier 4): slow compile, aggressive optimization (inlining, loop unrolling...)<br>
                <b>OSR</b> = On-Stack Replacement: a long-running loop switched to compiled code <i>while it was executing</i>.<br><br>
                That's why Java programs get faster after a "warm-up" - try the JIT example and compare round times.""";
            case "deopt" -> """
                <b>Deoptimization</b>: compiled code made an assumption (for example "this branch is never taken" or "this call is
                always to the same class") that turned out false, so the JVM threw the machine code away and went back to the interpreter.
                It will re-compile later with better information. Normal and healthy in small doses.""";
            case "thread-start" -> """
                A <b>new thread</b> started. Each thread has its own call <b>stack</b> but shares the <b>heap</b> with all other threads -
                which is why shared mutable data needs synchronization.""";
            case "thread-end" -> "A thread finished running and was destroyed. Its stack is gone; objects it created stay on the heap until unreachable.";
            case "sleep" -> """
                <code>Thread.sleep()</code>: the thread voluntarily stopped for a while. It is <b>not using the CPU</b> during this time
                (state TIMED_WAITING). Sleeping threads never appear in CPU profiler samples.""";
            case "monitor-enter" -> """
                <b>Lock contention!</b> A thread tried to enter a <code>synchronized</code> block but another thread already held the lock,
                so it had to <b>wait (BLOCKED)</b>. The duration is how long it waited. Long durations here mean threads are stepping on each other.""";
            case "monitor-wait" -> "A thread called <code>Object.wait()</code>: it released the lock and is waiting for another thread to call <code>notify()</code>/<code>notifyAll()</code> (or a timeout).";
            case "park" -> "A thread was <b>parked</b> by <code>LockSupport.park()</code> - the building block behind <code>ReentrantLock</code>, <code>CompletableFuture</code>, thread pools and most <code>java.util.concurrent</code> waiting.";
            case "exception" -> """
                An <b>exception (or error) was thrown</b>. JFR records the moment of the <code>throw</code>, even if your code catches it later.
                Creating an exception fills in a full stack trace, which is comparatively expensive - so don't use exceptions for normal control flow.""";
            default -> "";
        };
        StringBuilder sb = new StringBuilder(base);

        if (ev.jdk) 
            sb.append("<br><br><i>This is a JDK-internal event (not code you wrote). ").append("It is shown because \"Show JDK internals\" is on.</i>");
        return sb.toString();
    }

    public static String guideHtml() {
        
        return """
            <html><body style="font-family:sans-serif;font-size:12px;margin:12px">
            <h2>How to use this tool</h2>
            <ol>
              <li>Pick an <b>example</b> (top-left) or type your own program with a <code>main</code> method.</li>
              <li>Want the big picture first? Click <b>JVM Story Time</b> (top right) for a short, funny, narrated tour of what happens inside the JVM.</li>
              <li>Press <b>Compile &amp; Run</b> (Ctrl/Cmd+Enter). Your code is compiled, then run <i>inside this JVM</i> while JFR records what happens.</li>
              <li>Open the <b>Basics</b> tab and press <b>Next step</b>: it tells the story of your run in five short steps,
                  with a picture of the stack, Metaspace and heap.</li>
              <li>Ready for the raw data? Press the <b>Show advanced view &gt;&gt;</b> button (top right). It reveals the
                  <b>Event Log</b>, <b>Timeline</b>, <b>Heap &amp; GC</b>, <b>Insights</b>, <b>Class Loaders</b> and <b>Bytecode</b> tabs and the
                  <b>Inspector</b>. Click any event row: the Inspector explains it, shows the <b>call stack</b> and highlights the matching
                  <b>line of your source code</b>. Press <b>&lt;&lt; Back to beginner view</b> to return.</li>
            </ol>

            <h2>The life of a class</h2>
            <table border="0" cellpadding="6">
              <tr><td bgcolor="#E3F2FD"><b>1. Load</b></td><td>Find the <code>.class</code> file, create a <code>Class</code> object in Metaspace</td></tr>
              <tr><td bgcolor="#E0F2F1"><b>2. Link</b></td><td>Verify bytecode, prepare static fields (default values), resolve symbols</td></tr>
              <tr><td bgcolor="#E8F5E9"><b>3. Initialize</b></td><td>Run <code>&lt;clinit&gt;</code>: static fields and <code>static {}</code> blocks, once</td></tr>
              <tr><td bgcolor="#FFF3E0"><b>4. Use</b></td><td>Create objects (<code>new</code>), call methods, run threads</td></tr>
              <tr><td bgcolor="#EEEEEE"><b>5. Unload</b></td><td>When the class loader is garbage, the class goes with it</td></tr>
            </table>

            <h2>Where things live</h2>
            <ul>
              <li><b>Heap</b> - all objects and arrays. Shared by all threads. Cleaned by the garbage collector.</li>
              <li><b>Thread stack</b> - one per thread. One frame per method call: local variables, parameters, return address.</li>
              <li><b>Metaspace</b> - class metadata (methods, fields, constant pool). Native memory, not on the heap.</li>
              <li><b>Code cache</b> - machine code produced by the JIT compiler.</li>
            </ul>

            <h2>Good experiments</h2>
            <ul>
              <li>Run <i>Class lifecycle</i> and find when <code>Helper</code> loads - not at start, but on first use.</li>
              <li>Run <i>JIT warm-up</i>: the first rounds are slow (interpreter), later ones fast. Look at the JIT tab in the Insights view.</li>
              <li>Run <i>Garbage collection</i> and watch the Heap chart drop after each red GC line.</li>
              <li>Run <i>Threads &amp; locks</i>: find the <b>Lock contention</b> row where one thread waited for another.</li>
              <li>On the <b>Bytecode</b> tab, compare your source to what the JVM actually executes.</li>
            </ul>

            <h2>Honest limitations</h2>
            <ul>
              <li>JFR <b>samples</b> execution and allocation: tiny/fast things can be missed. The "STEP" rows and Console are exact.</li>
              <li>GC and heap numbers are JVM-wide, so they include this tool's own window.</li>
              <li>Your code runs inside the tool's JVM. <code>System.exit()</code> will close the tool; infinite loops stop after the timeout (30s) but cannot be force-killed.</li>
            </ul>
            </body></html>""";
    }
}
