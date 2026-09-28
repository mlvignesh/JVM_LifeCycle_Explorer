package jfr.content;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Examples {

    public static final Map<String, String> ALL = createExamples();

    private static Map<String, String> createExamples() {

        Map<String, String> m = new LinkedHashMap<>();

        m.put("1 \u00b7 Class lifecycle (start here)", """
            public class LifecycleDemo {

                // INITIALIZATION (<clinit>): runs once, before main()
                static String status = init("static field");

                static {
                    System.out.println("[static block] LifecycleDemo is being initialized");
                }

                static String init(String what) {
                    System.out.println("[clinit] " + what);
                    return "READY";
                }

                // Instance state lives on the HEAP, one copy per object
                private final int id;
                private final int[] payload = new int[50_000];   // ~200 KB

                LifecycleDemo(int id) {
                    this.id = id;
                    System.out.println("[constructor] creating object #" + id);
                }

                public static void main(String[] args) throws Exception {
                    System.out.println("[main] started, status = " + status);

                    // 'Helper' is NOT loaded yet: classes load lazily, on first use.
                    Thread.sleep(150);
                    System.out.println("[main] touching Helper for the first time...");
                    System.out.println("[main] " + Helper.greet());   // triggers LOAD + LINK + INIT of Helper

                    LifecycleDemo a = new LifecycleDemo(1);
                    LifecycleDemo b = new LifecycleDemo(2);
                    a.work(3);
                    System.out.println("[main] finished, b.id = " + b.id);
                }

                void work(int depth) throws Exception {
                    Thread.sleep(80);
                    if (depth > 0) work(depth - 1);   // every call pushes a new frame on the STACK
                }
            }

            class Helper {
                static { System.out.println("[static block] Helper is being initialized"); }
                static String greet() { return "Hello from Helper"; }
            }
            """);

        m.put("2 \u00b7 Objects & garbage collection", """
            import java.util.ArrayList;
            import java.util.List;

            public class GcDemo {
                static final List<byte[]> keep = new ArrayList<>();   // reachable = survives GC

                public static void main(String[] args) throws Exception {
                    for (int round = 1; round <= 6; round++) {
                        // Short-lived garbage: nothing references these after each iteration
                        for (int i = 0; i < 2_000; i++) {
                            byte[] garbage = new byte[16 * 1024];
                            garbage[0] = 1;
                        }
                        // Long-lived data: stays reachable through 'keep'
                        keep.add(new byte[512 * 1024]);
                        System.out.println("round " + round + ": keeping " + keep.size() + " x 512 KB");
                        Thread.sleep(100);
                    }
                    System.out.println("System.gc() while data is still reachable...");
                    System.gc();
                    keep.clear();
                    System.out.println("cleared the list, System.gc() again...");
                    System.gc();
                    System.out.println("done");
                }
            }
            """);

        m.put("3 \u00b7 JIT warm-up (interpreter to machine code)", """
            public class JitDemo {
                static int square(int x) { return x * x; }

                static long work(int n) {
                    long sum = 0;
                    for (int i = 0; i < n; i++) {
                        sum += square(i % 1000);
                    }
                    return sum;
                }

                public static void main(String[] args) {
                    for (int round = 1; round <= 10; round++) {
                        long t0 = System.nanoTime();
                        long result = work(8_000_000);
                        long micros = (System.nanoTime() - t0) / 1000;
                        System.out.println("round " + round + ": result=" + result + "  time=" + micros + " us");
                    }
                }
            }
            """);

        m.put("4 \u00b7 Threads, sleeping & lock contention", """
            public class ThreadsDemo {
                static final Object LOCK = new Object();
                static int counter = 0;

                static void sleep(long ms) {
                    try { Thread.sleep(ms); } catch (InterruptedException e) { }
                }

                public static void main(String[] args) throws Exception {
                    Thread holder = new Thread(() -> {
                        synchronized (LOCK) {
                            System.out.println("holder: got the lock, sleeping 80 ms WHILE HOLDING it");
                            sleep(80);
                            counter++;
                        }
                    }, "holder");

                    Thread waiter = new Thread(() -> {
                        sleep(20);
                        System.out.println("waiter: trying to enter the synchronized block...");
                        synchronized (LOCK) {
                            counter++;
                            System.out.println("waiter: finally got the lock");
                        }
                    }, "waiter");

                    holder.start();
                    waiter.start();
                    holder.join();
                    waiter.join();
                    System.out.println("counter = " + counter);
                }
            }
            """);

        m.put("5 \u00b7 Exceptions & the call stack", """
            public class StackDemo {
                static int depth = 0;

                static void recurse(int n) {
                    if (n == 0) throw new IllegalStateException("bottom reached");
                    recurse(n - 1);
                }

                static int divide(int a, int b) { return a / b; }

                static void deep(int n) {
                    depth = n;
                    deep(n + 1);           // never returns: the stack fills up
                }

                public static void main(String[] args) {
                    try { recurse(5); }
                    catch (IllegalStateException e) { System.out.println("caught: " + e.getMessage()); }

                    try { divide(1, 0); }
                    catch (ArithmeticException e) { System.out.println("caught: " + e.getMessage()); }

                    try { deep(0); }
                    catch (StackOverflowError e) { System.out.println("StackOverflowError after ~" + depth + " frames"); }
                }
            }
            """);

        m.put("6 \u00b7 Inheritance & initialization order", """
            public class InitOrderDemo {

                static class Parent {
                    static { System.out.println("1. Parent static block"); }
                    { System.out.println("3. Parent instance block"); }
                    Parent() { System.out.println("4. Parent constructor"); }
                }

                static class Child extends Parent {
                    static { System.out.println("2. Child static block"); }
                    { System.out.println("5. Child instance block"); }
                    Child() { System.out.println("6. Child constructor"); }
                }

                public static void main(String[] args) {
                    System.out.println("main started - Parent and Child are not loaded yet");
                    new Child();
                    System.out.println("--- second object: static blocks do NOT run again ---");
                    new Child();
                }
            }
            """);

        m.put("7 \u00b7 Linking vs Initialization (Step 2 vs Step 3)", """
            public class LinkingVsInitDemo {
                // LINKING (Preparation): 'counter' is allocated in Metaspace and set to default (0).
                // INITIALIZATION (<clinit>): 'counter' is assigned its actual value (42), and static block runs.
                static int counter = computeInitialValue();
                static final String CONSTANT = "Compile-time constant"; // inlined, does NOT trigger init!

                static {
                    System.out.println("[Step 3 - <clinit>] static block executed! counter = " + counter);
                }

                static int computeInitialValue() {
                    System.out.println("[Step 3 - <clinit>] computeInitialValue() called");
                    return 42;
                }

                public static void main(String[] args) {
                    System.out.println("[Step 4 - main()] main started!");
                    System.out.println("[Step 4 - main()] Accessing constant: " + CONSTANT);
                    System.out.println("[Step 4 - main()] Accessing static counter: " + counter);
                    System.out.println("[Step 4 - main()] Creating instance:");
                    new LinkingVsInitDemo();
                }

                LinkingVsInitDemo() {
                    System.out.println("[Step 4 - <init>] constructor executed for new instance");
                }
            }
            """);
        return m;
    }
}
