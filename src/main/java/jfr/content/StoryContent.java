package jfr.content;

public final class StoryContent {

    public static String seeLive(String label, int example) {

        return "<p style='margin-top:10px;'><font color='#1565C0'><b>Interactive Experiment:</b></font> <a href='example:" + example + "'>" + label + "</a>"
                + " &nbsp;(loads into editor &rarr; press <b>Compile &amp; Run</b>)</p>";
    }

    public static String storyHtml() {
        
        String code = "<table width='100%' bgcolor='#F8F9FA' style='border:1px solid #E9ECEF;border-radius:4px;font-family:monospace;font-size:11px;' cellpadding='8'><tr><td><pre style='margin:0;'>";
        String end = "</pre></td></tr></table>";
        String top = "<p align='right' style='margin:8px 0 0 0;'><small><a href='#top' style='color:#1976D2;text-decoration:none;'>&uarr; Back to Contents</a></small></p>";
        
        return "<html><body style='font-family:-apple-system,BlinkMacSystemFont,\"Segoe UI\",Roboto,sans-serif;font-size:12.5px;line-height:1.6;color:#212529;margin:16px;'>"
            + "<a name='top'></a>"
            
            // Header
            + "<div style='background-color:#F8F9FA;padding:16px;border-radius:8px;border-left:5px solid #E67E22;border:1px solid #E9ECEF;'>"
            + "<h1 style='margin:0 0 6px 0;color:#D35400;font-size:20px;'>&#9775; The Grand Voyage of a Java Class: The Straw Hat Grand Line Guide</h1>"
            + "<p style='margin:0;color:#6C757D;font-size:12.5px;'>Learning the HotSpot JVM doesn't have to feel like reading dry C++ source code! Follow your <code>.class</code> file as it sets sail from the East Blue to Laugh Tale across the JVM runtime — guided by Monkey D. Luffy and the Straw Hat crew. Every technical step is 100% JVM-spec accurate, just explained with pirate flair!</p>"
            + "</div>"
            
            // Table of Contents
            + "<h3 style='color:#34495E;margin-top:18px;'>Log Pose: Route to the Grand Line</h3><ol style='padding-left:20px;line-height:1.8;'>"
            + "<li><a href='#sec1' style='color:#1976D2;text-decoration:none;'>Step 1: Setting Sail &amp; Class Loading (Jinbe &amp; The ClassLoaders)</a></li>"
            + "<li><a href='#sec2' style='color:#1976D2;text-decoration:none;'>Step 2: Entering the Grand Line &amp; Linking (Zoro &amp; Robin Inspect the Ship)</a></li>"
            + "<li><a href='#sec3' style='color:#1976D2;text-decoration:none;'>Step 3: Class Initialization &lt;clinit&gt; (Franky Powers Up the Sunny!)</a></li>"
            + "<li><a href='#sec4' style='color:#1976D2;text-decoration:none;'>Step 4: The Execution Stack &amp; Frames (Sanji's LIFO Kitchen &amp; Orders)</a></li>"
            + "<li><a href='#sec5' style='color:#1976D2;text-decoration:none;'>Step 5: The Heap &amp; Object Anatomy (Luffy's Grand Banquet &amp; TLABs)</a></li>"
            + "<li><a href='#sec6' style='color:#1976D2;text-decoration:none;'>Step 6: Garbage Collection (Nami's Strict Island Cleaning &amp; Safe Havens)</a></li>"
            + "<li><a href='#sec7' style='color:#1976D2;text-decoration:none;'>Step 7: JIT Compilation (Luffy's Gears: Gear 2 to Gear 5 / C1 &amp; C2)</a></li>"
            + "<li><a href='#matrix' style='color:#1976D2;text-decoration:none;'>Straw Hat Crew to JVM Subsystem Bounty Board</a></li></ol>"

            // ---------------- SECTION 1
            + "<a name='sec1'></a>"
            + "<div style='border:1px solid #DEE2E6;background:#FFFFFF;padding:16px;border-radius:6px;margin-bottom:20px;'>"
            + "<h2 style='margin-top:0;color:#2C3E50;font-size:16px;border-bottom:2px solid #E9ECEF;padding-bottom:6px;'>1. Step 1: Setting Sail &amp; Class Loading (The ClassLoader Fleet)</h2>"
            + "<p>When you type <code>java Hello</code>, your raw <code>.class</code> file is just a dormant scroll of bytecode sitting on disk in the calm waters of the East Blue. To become an active part of the JVM world, it must be discovered and boarded into <b>Metaspace</b> by the <b>ClassLoader Fleet</b>, helmed by Helmsman <b>Jinbe</b>!</p>"
            
            + "<p><b>Parent-Delegation Model (Respecting the Fleet Hierarchy):</b> In the JVM (JVMS &sect;5.3), a loader never arrogantly loads a class itself first. Just like a pirate respecting the chain of command, it asks its parent first:</p>"
            
            + code
            + "+-------------------------------------------------------------------------+\n"
            + "|               THE GRAND FLEET CLASSLOADER DELEGATION                    |\n"
            + "+-------------------------------------------------------------------------+\n"
            + "|                                                                         |\n"
            + "|  [ 1. Bootstrap ClassLoader ]  (The Pirate King / JVM Core C++)         |\n"
            + "|          ^  |                  Loads: java.base (Object, String, Class) |\n"
            + "|  asks up    | \"Not mine!\"                                               |\n"
            + "|          |  v                                                           |\n"
            + "|  [ 2. Platform ClassLoader ]   (The Division Commanders)                |\n"
            + "|          ^  |                  Loads: java.sql, java.xml, jdk.*         |\n"
            + "|  asks up    | \"Not mine!\"                                               |\n"
            + "|          |  v                                                           |\n"
            + "|  [ 3. Application ClassLoader] (The Thousand Sunny Main Crew)           |\n"
            + "|          ^  |                  Loads: your application -classpath JARs  |\n"
            + "|  asks up    | \"Found it!\"                                               |\n"
            + "|          |  v                                                           |\n"
            + "|  [ 4. Custom ClassLoader ]     (The Grand Fleet Allies - isolated)      |\n"
            + "|                                Loads: dynamic scripts & plugin classes  |\n"
            + "+-------------------------------------------------------------------------+"
            + end

            + "<p><b>Where do they dock? (Metaspace):</b> Once Jinbe reads the <code>.class</code> stream, HotSpot creates an <code>InstanceKlass</code> metadata blueprint in native <b>Metaspace</b> (off-heap memory). The class blueprint gets its Constant Pool, method bytecodes, and vtable registered!</p>"
            + seeLive("See Live: Class Loading & Hierarchy in Action", 1)
            + "</div>" + top

            // ---------------- SECTION 2
            + "<a name='sec2'></a>"
            + "<div style='border:1px solid #DEE2E6;background:#FFFFFF;padding:16px;border-radius:6px;margin-bottom:20px;'>"
            + "<h2 style='margin-top:0;color:#2C3E50;font-size:16px;border-bottom:2px solid #E9ECEF;padding-bottom:6px;'>2. Step 2: Entering the Grand Line &amp; Linking (Zoro &amp; Robin on Watch)</h2>"
            + "<p>Before any class can run on the Thousand Sunny, it must pass inspection through <b>Reverse Mountain</b>. This is <b>Linking</b> (JVMS &sect;5.4), consisting of 3 critical checkpoints:</p>"

            + code
            + "+-------------------------------------------------------------------------+\n"
            + "|                          STEP 2: LINKING PHASES                         |\n"
            + "+-------------------+-----------------------------------------------------+\n"
            + "| Phase             | Straw Hat Inspection Duty                           |\n"
            + "+-------------------+-----------------------------------------------------+\n"
            + "| 1. Verification   | Zoro's Observation Haki:                            |\n"
            + "|                   | - Checks the '0xCAFEBABE' magic header signature    |\n"
            + "|                   | - Checks StackMapTable: ensures no stack-smashing   |\n"
            + "|                   | - If rogue bytecode is detected &rarr; VerifyError!  |\n"
            + "+-------------------+-----------------------------------------------------+\n"
            + "| 2. Preparation    | Usopp builds empty storage chests:                  |\n"
            + "|                   | - Allocates memory for static fields in Metaspace   |\n"
            + "|                   | - Initializes to ZERO defaults (0, null, false)     |\n"
            + "|                   | - (Note: your custom values like 42 do NOT set yet!)|\n"
            + "+-------------------+-----------------------------------------------------+\n"
            + "| 3. Resolution     | Robin decodes the Poneglyphs:                       |\n"
            + "|                   | - Replaces symbolic names (e.g. #14 \"print\")        |\n"
            + "|                   |   with real direct memory addresses in Metaspace    |\n"
            + "+-------------------+-----------------------------------------------------+"
            + end

            + "<p><b>Key Beginner Catch:</b> In Preparation, <code>static int bounties = 100_000_000;</code> is given the default value <b>0</b>! The 100 million won't appear until Step 3.</p>"
            + seeLive("See Live: Step 2 Default 0 vs Step 3 Initialized 42", 7)
            + "</div>" + top

            // ---------------- SECTION 3
            + "<a name='sec3'></a>"
            + "<div style='border:1px solid #DEE2E6;background:#FFFFFF;padding:16px;border-radius:6px;margin-bottom:20px;'>"
            + "<h2 style='margin-top:0;color:#2C3E50;font-size:16px;border-bottom:2px solid #E9ECEF;padding-bottom:6px;'>3. Step 3: Class Initialization &lt;clinit&gt; (Franky Powers Up the Sunny!)</h2>"
            + "<p>Now shipwright <b>Franky</b> yells <i>\"SUUUUPER!\"</i> and lights the cola boilers! This is <b>Initialization</b> (JVMS &sect;5.5), where HotSpot runs the synthesized static constructor: <code>&lt;clinit&gt;()</code>.</p>"

            + code
            + "+-------------------------------------------------------------------------+\n"
            + "|                  CLASS INITIALIZATION (&lt;clinit&gt;) SEQUENCE                 |\n"
            + "+-------------------------------------------------------------------------+\n"
            + "|                                                                         |\n"
            + "|  1. Trigger Event       (Someone calls a static method or creates 'new')|\n"
            + "|           |                                                             |\n"
            + "|           v                                                             |\n"
            + "|  2. Parent First        (Superclasses initialize before subclasses!)    |\n"
            + "|           |                                                             |\n"
            + "|           v                                                             |\n"
            + "|  3. Class Lock Taken    (Thread-safe: only ONE thread runs &lt;clinit&gt;)      |\n"
            + "|           |                                                             |\n"
            + "|           v                                                             |\n"
            + "|  4. Run &lt;clinit&gt;()       (Static variables & static { ... } blocks fire!)|\n"
            + "|           |                                                             |\n"
            + "|           v                                                             |\n"
            + "|  5. Set to 'Ready'      (Static variables now have real user values!)   |\n"
            + "|                                                                         |\n"
            + "+-------------------------------------------------------------------------+"
            + end

            + "<p><b>The 'Compile-Time Constant' Shortcut:</b> If a field is <code>static final int MAX = 100;</code>, the compiler inlines the number directly into caller code without even waking up Franky or initializing the class!</p>"
            + seeLive("See Live: Parent vs Child Static & Instance Init Order", 6)
            + "</div>" + top

            // ---------------- SECTION 4
            + "<a name='sec4'></a>"
            + "<div style='border:1px solid #DEE2E6;background:#FFFFFF;padding:16px;border-radius:6px;margin-bottom:20px;'>"
            + "<h2 style='margin-top:0;color:#2C3E50;font-size:16px;border-bottom:2px solid #E9ECEF;padding-bottom:6px;'>4. Step 4: The Execution Stack &amp; Frames (Sanji's Kitchen)</h2>"
            + "<p>Every active thread in Java has its own private <b>JVM Stack</b>. Think of it like Chef <b>Sanji</b>'s order board in the galley: every time a method is called, a new <b>Stack Frame</b> is placed on top (LIFO: Last-In, First-Out). When the recipe finishes, the plate is served and the frame pops off!</p>"

            + code
            + "+-------------------------------------------------------------------------+\n"
            + "|                        INSIDE A JVM STACK FRAME                         |\n"
            + "+-------------------------------------------------------------------------+\n"
            + "|  [ Current Active Frame: cookMeal(ingredients) ]                        |\n"
            + "|  +-------------------------------------------------------------------+  |\n"
            + "|  | 1. Local Variable Array (LVA)                                     |  |\n"
            + "|  |    Slot 0: this (the chef) | Slot 1: meat | Slot 2: spice         |  |\n"
            + "|  +-------------------------------------------------------------------+  |\n"
            + "|  | 2. Operand Stack (OS) (Sanji's chopping board calculation area)   |  |\n"
            + "|  |    Push 20 meat &rarr; Push 5 spice &rarr; 'iadd' pops &rarr; Pushes 25    |  |\n"
            + "|  +-------------------------------------------------------------------+  |\n"
            + "|  | 3. Frame Data & Return PC                                         |  |\n"
            + "|  |    Points to Metaspace constant pool & remembers caller address   |  |\n"
            + "|  +-------------------------------------------------------------------+  |\n"
            + "|                                                                         |\n"
            + "|  [ Caller Frame: main(String[] args) ]                                  |\n"
            + "|  +-------------------------------------------------------------------+  |\n"
            + "|  | Local Variables | Operand Stack | Frame Data                      |  |\n"
            + "|  +-------------------------------------------------------------------+  |\n"
            + "+-------------------------------------------------------------------------+"
            + end

            + "<p><b>What happens in infinite recursion?</b> If Luffy orders food in an infinite loop without returning, the order board runs out of space: <code>java.lang.StackOverflowError</code>!</p>"
            + seeLive("See Live: Stack Frames & StackOverflowError", 5)
            + "</div>" + top

            // ---------------- SECTION 5
            + "<a name='sec5'></a>"
            + "<div style='border:1px solid #DEE2E6;background:#FFFFFF;padding:16px;border-radius:6px;margin-bottom:20px;'>"
            + "<h2 style='margin-top:0;color:#2C3E50;font-size:16px;border-bottom:2px solid #E9ECEF;padding-bottom:6px;'>5. Step 5: The Java Heap &amp; Object Anatomy (Luffy's Grand Banquet)</h2>"
            + "<p>While method calls live briefly on the Stack, every object created with <code>new</code> is feasting on the open <b>Java Heap</b>! To prevent thread collisions when multiple crew members grab memory, HotSpot gives each thread its own <b>TLAB</b> (Thread-Local Allocation Buffer) for lightning-fast lock-free allocation.</p>"

            + code
            + "+-------------------------------------------------------------------------+\n"
            + "|                 ANATOMY OF A JAVA OBJECT ON THE HEAP                    |\n"
            + "+-------------------------------------------------------------------------+\n"
            + "|                                                                         |\n"
            + "|  +-------------------------------------------------------------------+  |\n"
            + "|  | 1. Mark Word (8 bytes / 64 bits)                                  |  |\n"
            + "|  |    - Identity HashCode (31 bits)                                  |  |\n"
            + "|  |    - Age / Tenuring level (4 bits: 0 to 15)                      |  |\n"
            + "|  |    - Thread Lock / Synchronization status                         |  |\n"
            + "|  +-------------------------------------------------------------------+  |\n"
            + "|  | 2. Klass Word Pointer (4 or 8 bytes)                              |  |\n"
            + "|  |    - Compressed pointer back to Metaspace blueprint               |  |\n"
            + "|  +-------------------------------------------------------------------+  |\n"
            + "|  | 3. Instance Fields Data                                           |  |\n"
            + "|  |    - The actual meat: int hp, String name, double bounty          |  |\n"
            + "|  +-------------------------------------------------------------------+  |\n"
            + "|  | 4. Padding (Padded to clean 8-byte boundary)                      |  |\n"
            + "|  +-------------------------------------------------------------------+  |\n"
            + "|                                                                         |\n"
            + "+-------------------------------------------------------------------------+"
            + end

            + "<p><b>Stack vs Heap Link:</b> Sanji's stack frame has a local variable holding a <i>pointer reference</i>, but the actual giant roast meat object lives on the Heap!</p>"
            + seeLive("See Live: Dynamic Heap Allocation & Object Creation", 2)
            + "</div>" + top

            // ---------------- SECTION 6
            + "<a name='sec6'></a>"
            + "<div style='border:1px solid #DEE2E6;background:#FFFFFF;padding:16px;border-radius:6px;margin-bottom:20px;'>"
            + "<h2 style='margin-top:0;color:#2C3E50;font-size:16px;border-bottom:2px solid #E9ECEF;padding-bottom:6px;'>6. Step 6: Garbage Collection (Navigator Nami Cleans the Deck)</h2>"
            + "<p>After the banquet, leftover bone objects clutter the ship. <b>Navigator Nami</b> runs the <b>Garbage Collector</b>! Guided by the <i>Weak Generational Hypothesis</i> (most temporary objects die young), the Heap is divided into distinct zones:</p>"

            + code
            + "+-------------------------------------------------------------------------+\n"
            + "|                     THE GENERATIONAL HEAP TOPOLOGY                      |\n"
            + "+----------------------------------------------------+--------------------+\n"
            + "|            YOUNG GENERATION (East Blue)            | OLD GEN (Grand Line|\n"
            + "| +------------------------+-----------+-----------+ | +----------------+ |\n"
            + "| |      Eden Space        | Survivor0 | Survivor1 | | |    Tenured     | |\n"
            + "| | (TLAB fast allocation) |  (From)   |   (To)    | | | (Old Veterans) | |\n"
            + "| +------------------------+-----------+-----------+ | +----------------+ |\n"
            + "+----------------------------------------------------+--------------------+\n"
            + "      |                          |                           ^\n"
            + "      +--- Minor GC Copy Cycle --+--- Survived 15 Battles ---+"
            + end

            + "<p><b>GC Roots &amp; Safepoints:</b> Nami traces reachability from <b>GC Roots</b> (active stack variables, static fields). If nobody on the crew holds a reference to an object, it gets reclaimed! When a major GC runs, mutator threads pause at <b>Safepoints</b> (Stop-The-World) while memory is compacted.</p>"
            + seeLive("See Live: Trigger GC Pauses & Inspect Heap Live Graph", 2)
            + "</div>" + top

            // ---------------- SECTION 7
            + "<a name='sec7'></a>"
            + "<div style='border:1px solid #DEE2E6;background:#FFFFFF;padding:16px;border-radius:6px;margin-bottom:20px;'>"
            + "<h2 style='margin-top:0;color:#2C3E50;font-size:16px;border-bottom:2px solid #E9ECEF;padding-bottom:6px;'>7. Step 7: Tiered JIT Compilation (Luffy's Gears: Base &rarr; Gear 5)</h2>"
            + "<p>When the JVM starts, it interprets bytecode one instruction at a time (like Luffy fighting in base form). But when a method is called thousands of times in a hot loop, the <b>Just-In-Time (JIT) Compilers</b> power up through the tiers!</p>"

            + code
            + "+-------------------------------------------------------------------------+\n"
            + "|                    TIERED COMPILATION GEAR TRANSFORMATION               |\n"
            + "+-------------------------------------------------------------------------+\n"
            + "|                                                                         |\n"
            + "|  [ Tier 0: The Bytecode Interpreter ] (Base Form)                       |\n"
            + "|    - Starts immediately, counts invocations & loop back-edges           |\n"
            + "|           |                                                             |\n"
            + "|           v (Called ~2,000 times)                                       |\n"
            + "|  [ Tier 1-3: C1 Client Compiler ]     (Gear 2 / Gear 3)                 |\n"
            + "|    - Compiles to native CPU code quickly with profiling instrumentation |\n"
            + "|           |                                                             |\n"
            + "|           v (Called ~10,000 times: HOT METHOD!)                         |\n"
            + "|  [ Tier 4: C2 Server Compiler ]     (Gear 5: Peak Liberation)          |\n"
            + "|    - Extreme optimization: method inlining, loop unrolling, escape anal.|\n"
            + "|    - Lightning native machine assembly saved in Code Cache!             |\n"
            + "|           |                                                             |\n"
            + "|           v (Rare type assumption violated!)                            |\n"
            + "|  [ Deoptimization / Uncommon Trap ]                                     |\n"
            + "|    - Falls back to Interpreter safely without crashing                  |\n"
            + "|                                                                         |\n"
            + "+-------------------------------------------------------------------------+"
            + end

            + "<p><b>On-Stack Replacement (OSR):</b> If a single loop runs 100,000 times inside an interpreted method, C2 compiles the loop on the fly and swaps the active stack frame right on the CPU without waiting for the method to return!</p>"
            + seeLive("See Live: JIT Tiered Compilation & Microsecond Warmup", 3)
            + "</div>" + top

            // ---------------- SECTION 8 (MATRIX)
            + "<a name='matrix'></a>"
            + "<h2 style='color:#2C3E50;font-size:16px;'>8. Straw Hat Crew to JVM Subsystem Bounty Board</h2>"
            + "<table border='1' cellpadding='7' cellspacing='0' width='100%' style='border-collapse:collapse;border-color:#DEE2E6;font-size:12px;'>"
            + "<tr bgcolor='#F8F9FA' style='color:#2C3E50;'>"
            + "<td width='18%'><b>Straw Hat Crew</b></td>"
            + "<td width='22%'><b>JVM Subsystem</b></td>"
            + "<td width='20%'><b>Memory Region</b></td>"
            + "<td><b>JVM Responsibility</b></td>"
            + "</tr>"
            + "<tr>"
            + "<td><b>Jinbe (Helmsman)</b></td>"
            + "<td><b>ClassLoaders</b></td>"
            + "<td>Metaspace (Off-Heap)</td>"
            + "<td>Guides <code>.class</code> bytecode streams through parent delegation and creates <code>InstanceKlass</code> blueprints.</td>"
            + "</tr>"
            + "<tr>"
            + "<td><b>Zoro (Observation)</b></td>"
            + "<td><b>Bytecode Verifier</b></td>"
            + "<td>Metaspace (Native)</td>"
            + "<td>Verifies <code>0xCAFEBABE</code> headers, type safety, and StackMapTable invariants before code execution.</td>"
            + "</tr>"
            + "<tr>"
            + "<td><b>Franky (Shipwright)</b></td>"
            + "<td><b>Class Initializer</b></td>"
            + "<td>Metaspace &amp; Static State</td>"
            + "<td>Runs <code>&lt;clinit&gt;</code> to set real values for static fields and execute static initializer blocks.</td>"
            + "</tr>"
            + "<tr>"
            + "<td><b>Sanji (Chef)</b></td>"
            + "<td><b>JVM Call Stack</b></td>"
            + "<td>Thread Stack (Native)</td>"
            + "<td>Pushes and pops method execution frames containing Local Variable Arrays and Operand Stacks.</td>"
            + "</tr>"
            + "<tr>"
            + "<td><b>Luffy (Captains Feast)</b></td>"
            + "<td><b>Java Heap &amp; TLAB</b></td>"
            + "<td>Java Heap (Eden/Survivor)</td>"
            + "<td>Where all objects and arrays feast; fast lock-free allocation through Thread-Local Allocation Buffers.</td>"
            + "</tr>"
            + "<tr>"
            + "<td><b>Nami (Navigator)</b></td>"
            + "<td><b>Garbage Collector</b></td>"
            + "<td>Java Heap</td>"
            + "<td>Traces GC roots, reclaims unreachable objects, promotes survivors, and triggers Safepoint STW pauses.</td>"
            + "</tr>"
            + "<tr>"
            + "<td><b>Luffy (Gears 2 &rarr; 5)</b></td>"
            + "<td><b>JIT Compilers (C1/C2)</b></td>"
            + "<td>Code Cache (Off-Heap)</td>"
            + "<td>Accelerates interpreted bytecode into peak native CPU machine code with escape analysis and inlining.</td>"
            + "</tr>"
            + "</table>"
            + "<p style='margin-top:20px;text-align:center;color:#6C757D;'>Select any example from the dropdown and click <b>Compile &amp; Run</b> to trace this grand journey in real time via JFR.</p>"
            + top
            + "</body></html>";
    }
}
