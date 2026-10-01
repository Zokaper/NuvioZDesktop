import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/**
 * In-process perf driver for an unmodified Nuvio desktop jar set.
 * - EDT heartbeat: measures how long a trivial runnable waits on the AWT event queue,
 *   samples the EDT stack mid-stall (same method as the repo's EdtStallWatchdog).
 * - Command file: perf.dir/cmd.txt, polled every 100 ms. Lines:
 *     mark <phase>              start a new measurement phase
 *     wheel <x> <y> <rot> <intervalMs> <count>   post mouse wheel events at window coords
 *     hwheel <x> <y> <rot> <intervalMs> <count>  the same with Shift held (horizontal scroll)
 *     click <x> <y>             post press/release/click at window coords
 *     info                      dump windows / components
 *     gc                        log GC + CPU counters
 * Phases are summarised into perf.dir/phases.txt.
 */
public class PerfDriver {
    static Path dir;
    static PrintWriter log;
    static final long T0 = System.nanoTime();
    static volatile String phase = "startup";
    static volatile Thread edt;
    static final Map<String, Stats> stats = Collections.synchronizedMap(new LinkedHashMap<>());

    static class Stats {
        long frames, f16, f25, f33, f50, f100, lastFrameEnd, gaps33, gaps50, gaps100; java.util.ArrayList<Long> frameMs = new java.util.ArrayList<>(), frameCpu = new java.util.ArrayList<>();
        long tasks, t8, t16, t33, t50, t100, t250, busyNs, jankNs, maxTaskMs;
        long beats, over16, over34, over50, over100, over250, over500, over1000, stalledMs, maxMs;
        long startNs = System.nanoTime(), endNs;
        long cpuStart = processCpuNs(), cpuEnd;
        long gcStart = gcMs(), gcEnd, gcCountStart = gcCount(), gcCountEnd;
        long allocStart = allocBytes(), allocEnd;
    }

    static long ms() { return (System.nanoTime() - T0) / 1_000_000L; }

    static synchronized void log(String s) {
        log.println(String.format("%7d %d %-14s %s", ms(), System.currentTimeMillis(), phase, s.startsWith("cmd: mark") ? s + " nanoMs=" + System.nanoTime() / 1_000_000L + " rel=" + ms() : s));
    }

    /**
     * In-memory java.util.prefs, selected with
     * -Djava.util.prefs.PreferencesFactory=PerfDriver$MemoryPrefsFactory (run.py always passes it).
     * On Windows the default factory is the registry, HKCU\Software\JavaSoft\Prefs, which is where
     * supabase-kt's default storage left the official login that vanilla Nuvio and older Z builds share.
     * A build under test must never read, move or refresh that login, so nothing here touches it.
     */
    public static class MemoryPrefsFactory implements java.util.prefs.PreferencesFactory {
        private static final java.util.prefs.Preferences USER = new MemoryPrefs(null, "");
        private static final java.util.prefs.Preferences SYSTEM = new MemoryPrefs(null, "");
        public java.util.prefs.Preferences userRoot() { return USER; }
        public java.util.prefs.Preferences systemRoot() { return SYSTEM; }
    }

    static class MemoryPrefs extends java.util.prefs.AbstractPreferences {
        private final Map<String, String> values = new HashMap<>();
        private final Map<String, MemoryPrefs> children = new HashMap<>();
        MemoryPrefs(MemoryPrefs parent, String name) { super(parent, name); }
        protected void putSpi(String key, String value) { values.put(key, value); }
        protected String getSpi(String key) { return values.get(key); }
        protected void removeSpi(String key) { values.remove(key); }
        protected void removeNodeSpi() { values.clear(); children.clear(); }
        protected String[] keysSpi() { return values.keySet().toArray(new String[0]); }
        protected String[] childrenNamesSpi() { return children.keySet().toArray(new String[0]); }
        protected java.util.prefs.AbstractPreferences childSpi(String name) {
            return children.computeIfAbsent(name, n -> new MemoryPrefs(this, n));
        }
        protected void syncSpi() { }
        protected void flushSpi() { }
    }

    public static void main(String[] args) throws Exception {
        dir = Paths.get(System.getProperty("perf.dir"));
        Files.createDirectories(dir);
        log = new PrintWriter(Files.newBufferedWriter(dir.resolve("driver.log")), true);
        stats.put(phase, new Stats());
        log("driver start; main=" + System.getProperty("perf.main"));
        String prefs = java.util.prefs.Preferences.userRoot().getClass().getName();
        log("prefs=" + prefs);
        if (!prefs.endsWith("MemoryPrefs") && !Boolean.getBoolean("perf.allowRegistryPrefs")) {
            log("refusing to start: java.util.prefs is not the in-memory factory");
            System.exit(3);
        }
        Thread hb = new Thread(PerfDriver::heartbeat, "perf-edt-heartbeat");
        hb.setDaemon(true);
        hb.setPriority(Thread.MAX_PRIORITY);
        hb.start();
        Thread cmd = new Thread(PerfDriver::commands, "perf-commands");
        cmd.setDaemon(true);
        cmd.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { writeSummary(); writeFrames(); }));
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new TimingQueue());
        Class.forName(System.getProperty("perf.main", "com.nuvio.app.MainKt"))
            .getMethod("main", String[].class).invoke(null, (Object) args);
    }

    static class TimingQueue extends EventQueue {
        // UI-thread CPU time per task as well as wall time: under other processes' load the wall time
        // includes waiting for a core, the CPU time does not.
        final ThreadMXBean cpuClock = ManagementFactory.getThreadMXBean();

        @Override protected void dispatchEvent(AWTEvent e) {
            long t = System.nanoTime();
            long c = cpuClock.getCurrentThreadCpuTime();
            try { super.dispatchEvent(e); } finally {
                long d = System.nanoTime() - t;
                long cpu = cpuClock.getCurrentThreadCpuTime() - c;
                Stats s = stats.get(phase);
                boolean frame = false;
                if (e instanceof java.awt.event.InvocationEvent) {
                    String ps = e.paramString();
                    frame = ps.contains("skiko.FrameDispatcher");
                }
                if (frame && s != null) synchronized (s) {
                    long ms = d / 1_000_000L;
                    s.frames++; s.frameMs.add(d / 100_000L); s.frameCpu.add(cpu / 100_000L);
                    if (ms >= 16) s.f16++;
                    if (ms >= 25) s.f25++;
                    if (ms >= 33) s.f33++;
                    if (ms >= 50) s.f50++;
                    if (ms >= 100) s.f100++;
                    long now = System.nanoTime();
                    if (s.lastFrameEnd != 0) {
                        long gap = (now - s.lastFrameEnd) / 1_000_000L;
                        if (gap < 1000) {
                            if (gap >= 33) s.gaps33++;
                            if (gap >= 50) s.gaps50++;
                            if (gap >= 100) s.gaps100++;
                        }
                    }
                    s.lastFrameEnd = now;
                }
                if (frame && d >= 25_000_000L) {
                    long endEpoch = System.currentTimeMillis();
                    log("FRAME " + (d / 1_000_000L) + "ms startEpoch=" + (endEpoch - d / 1_000_000L) + " endEpoch=" + endEpoch);
                }
                if (s != null) synchronized (s) {
                    s.tasks++; s.busyNs += d;
                    long ms = d / 1_000_000L;
                    if (ms >= 8) s.t8++;
                    if (ms >= 16) { s.t16++; s.jankNs += d; }
                    if (ms >= 33) s.t33++;
                    if (ms >= 50) s.t50++;
                    if (ms >= 100) s.t100++;
                    if (ms >= 250) s.t250++;
                    s.maxTaskMs = Math.max(s.maxTaskMs, ms);
                }
                if (d >= 100_000_000L) {
                    String what = e.getClass().getSimpleName();
                    if (e instanceof java.awt.event.InvocationEvent) {
                        String ps = e.paramString();
                        int i = ps.indexOf("runnable=");
                        if (i >= 0) what += " " + ps.substring(i, Math.min(ps.length(), i + 110));
                    }
                    log("TASK " + (d / 1_000_000L) + "ms cpu=" + (cpu / 1_000_000L) + "ms " + what);
                }
            }
        }
    }

    static void heartbeat() {
        try {
            while (true) {
                long posted = System.nanoTime();
                AtomicLong ran = new AtomicLong();
                SwingUtilities.invokeLater(() -> { edt = Thread.currentThread(); ran.set(System.nanoTime()); });
                StackTraceElement[] first = null;
                long firstAt = 0;
                Map<String, Integer> topFrames = new LinkedHashMap<>();
                while (ran.get() == 0L) {
                    Thread.sleep(5);
                    long waited = (System.nanoTime() - posted) / 1_000_000L;
                    if (waited < 34) continue;
                    Thread t = edt;
                    if (t == null) continue;
                    StackTraceElement[] st = t.getStackTrace();
                    if (first == null) { first = st; firstAt = waited; }
                    String key = summarize(st, 6);
                    topFrames.merge(key, 1, Integer::sum);
                }
                long waitedMs = (ran.get() - posted) / 1_000_000L;
                Stats s = stats.get(phase);
                if (s != null) {
                    synchronized (s) {
                        s.beats++;
                        if (waitedMs > 16) s.over16++;
                        if (waitedMs > 34) { s.over34++; s.stalledMs += waitedMs; }
                        if (waitedMs > 50) s.over50++;
                        if (waitedMs > 100) s.over100++;
                        if (waitedMs > 250) s.over250++;
                        if (waitedMs > 500) s.over500++;
                        if (waitedMs > 1000) s.over1000++;
                        s.maxMs = Math.max(s.maxMs, waitedMs);
                    }
                }
                if (waitedMs >= 50) {
                    StringBuilder b = new StringBuilder("STALL " + waitedMs + "ms");
                    for (Map.Entry<String, Integer> e : topFrames.entrySet()) {
                        b.append("\n      [").append(e.getValue()).append("x] ").append(e.getKey());
                    }
                    if (first != null) b.append("\n      first@" + firstAt + "ms:\n").append(fullStack(first, 40));
                    log(b.toString());
                }
                Thread.sleep(4);
            }
        } catch (Throwable t) {
            log("heartbeat died " + t);
        }
    }

    static boolean boring(String c) {
        return c.startsWith("java.") || c.startsWith("javax.") || c.startsWith("sun.") || c.startsWith("jdk.")
            || c.startsWith("kotlin.") || c.startsWith("kotlinx.coroutines.") || c.startsWith("androidx.compose.runtime.")
            || c.startsWith("org.jetbrains.skiko.") ;
    }

    static String summarize(StackTraceElement[] st, int n) {
        StringBuilder b = new StringBuilder();
        int added = 0;
        if (st.length > 0) b.append(st[0].getClassName()).append('.').append(st[0].getMethodName()).append(" <- ");
        for (StackTraceElement e : st) {
            if (boring(e.getClassName())) continue;
            b.append(e.getClassName().replace("com.nuvio.app.", "~")).append('.').append(e.getMethodName())
                .append(':').append(e.getLineNumber()).append(" < ");
            if (++added >= n) break;
        }
        return b.toString();
    }

    static String fullStack(StackTraceElement[] st, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(n, st.length); i++) b.append("        at ").append(st[i]).append('\n');
        return b.toString();
    }

    static void commands() {
        Path f = dir.resolve("cmd.txt");
        while (true) {
            try {
                Thread.sleep(100);
                if (!Files.exists(f)) continue;
                List<String> lines = Files.readAllLines(f);
                Files.delete(f);
                for (String line : lines) run(line.trim());
            } catch (Throwable t) {
                log("command error " + t);
            }
        }
    }

    static void run(String line) throws Exception {
        if (line.isEmpty()) return;
        String[] p = line.split("\\s+");
        log("cmd: " + line);
        switch (p[0]) {
            case "mark": {
                Stats old = stats.get(phase);
                if (old != null) closeStats(old);
                phase = p[1];
                stats.put(phase, new Stats());
                writeSummary();
                writeFrames();
                break;
            }
            case "wheel": {
                int x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
                int rot = Integer.parseInt(p[3]);
                long interval = Long.parseLong(p[4]);
                int count = Integer.parseInt(p[5]);
                for (int i = 0; i < count; i++) {
                    post(x, y, (target, pt) -> new MouseWheelEvent(target, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0,
                        pt.x, pt.y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rot));
                    Thread.sleep(interval);
                }
                break;
            }
            case "hwheel": {
                // Shift + wheel: Compose desktop scrolls horizontally (e.g. along the Continue Watching row).
                int x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
                int rot = Integer.parseInt(p[3]);
                long interval = Long.parseLong(p[4]);
                int count = Integer.parseInt(p[5]);
                for (int i = 0; i < count; i++) {
                    post(x, y, (target, pt) -> new MouseWheelEvent(target, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), InputEvent.SHIFT_DOWN_MASK,
                        pt.x, pt.y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rot));
                    Thread.sleep(interval);
                }
                break;
            }
            case "move": {
                int x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
                post(x, y, (t, pt) -> new MouseEvent(t, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, pt.x, pt.y, 0, false, 0));
                break;
            }
            case "click": {
                int x = Integer.parseInt(p[1]), y = Integer.parseInt(p[2]);
                post(x, y, (t, pt) -> new MouseEvent(t, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, pt.x, pt.y, 0, false, 0));
                Thread.sleep(60);
                post(x, y, (t, pt) -> new MouseEvent(t, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), InputEvent.BUTTON1_DOWN_MASK, pt.x, pt.y, 1, false, MouseEvent.BUTTON1));
                Thread.sleep(60);
                post(x, y, (t, pt) -> new MouseEvent(t, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, pt.x, pt.y, 1, false, MouseEvent.BUTTON1));
                post(x, y, (t, pt) -> new MouseEvent(t, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, pt.x, pt.y, 1, false, MouseEvent.BUTTON1));
                break;
            }
            case "info": {
                SwingUtilities.invokeAndWait(() -> {
                    for (Window w : Window.getWindows()) {
                        log("window " + w.getClass().getName() + " visible=" + w.isShowing() + " bounds=" + w.getBounds()
                            + " scale=" + (w.getGraphicsConfiguration() == null ? "?" : w.getGraphicsConfiguration().getDefaultTransform().getScaleX()));
                        if (w.isShowing()) dump(w, 1);
                    }
                });
                break;
            }
            case "gc": {
                log("cpuMs=" + processCpuNs() / 1_000_000 + " gcMs=" + gcMs() + " gcCount=" + gcCount() + " allocMB=" + allocBytes() / 1_048_576
                    + " heapUsedMB=" + ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1_048_576
                    + " threads=" + ManagementFactory.getThreadMXBean().getThreadCount());
                break;
            }
            case "threads": {
                ThreadMXBean tb = ManagementFactory.getThreadMXBean();
                StringBuilder b = new StringBuilder("threads cpu:\n");
                List<long[]> rows = new ArrayList<>();
                Map<Long, String> names = new HashMap<>();
                for (ThreadInfo ti : tb.getThreadInfo(tb.getAllThreadIds())) {
                    if (ti == null) continue;
                    long cpu = tb.getThreadCpuTime(ti.getThreadId());
                    rows.add(new long[]{cpu, ti.getThreadId()});
                    names.put(ti.getThreadId(), ti.getThreadName() + " " + ti.getThreadState());
                }
                rows.sort((a, c) -> Long.compare(c[0], a[0]));
                for (int i = 0; i < Math.min(40, rows.size()); i++)
                    b.append(String.format("   %8d ms  %s%n", rows.get(i)[0] / 1_000_000, names.get(rows.get(i)[1])));
                log(b.toString());
                break;
            }
            case "close": {
                SwingUtilities.invokeLater(() -> {
                    Window w = mainWindow();
                    if (w != null) w.dispatchEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSING));
                });
                break;
            }
            default:
                log("unknown command");
        }
    }

    interface EventFactory { AWTEvent make(Component target, Point p); }

    static void post(int x, int y, EventFactory f) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Window w = mainWindow();
            if (w == null) { log("no window"); return; }
            Container root = ((RootPaneContainer) w).getRootPane().getLayeredPane();
            Point inRoot = SwingUtilities.convertPoint(w, x, y, root);
            Component target = SwingUtilities.getDeepestComponentAt(root, inRoot.x, inRoot.y);
            if (target == null) target = root;
            Point pt = SwingUtilities.convertPoint(w, x, y, target);
            Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(f.make(target, pt));
        });
    }

    static Window mainWindow() {
        Window best = null;
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || !(w instanceof RootPaneContainer)) continue;
            if (best == null || w.getWidth() * w.getHeight() > best.getWidth() * best.getHeight()) best = w;
        }
        return best;
    }

    static void dump(Component c, int depth) {
        if (depth > 9) return;
        log("  ".repeat(depth) + c.getClass().getName() + " " + c.getBounds() + (c.isLightweight() ? "" : " HEAVY"));
        if (c instanceof Container) for (Component k : ((Container) c).getComponents()) dump(k, depth + 1);
    }

    static void closeStats(Stats s) {
        s.endNs = System.nanoTime();
        s.cpuEnd = processCpuNs();
        s.gcEnd = gcMs();
        s.gcCountEnd = gcCount();
        s.allocEnd = allocBytes();
    }

    static synchronized void writeSummary() {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("phases.txt")))) {
            w.println("phase           durS  tasks  busy%  >8ms >16ms >33ms >50ms >100 >250 jank16ms maxTask | beats  >16  >34  >50 >100 >250 >500 >1s  stalled34ms  maxMs  cpuCores  gcMs gcN  allocMB");
            for (Map.Entry<String, Stats> e : stats.entrySet()) {
                Stats s = e.getValue();
                long end = s.endNs == 0 ? System.nanoTime() : s.endNs;
                long cpu = (s.cpuEnd == 0 ? processCpuNs() : s.cpuEnd) - s.cpuStart;
                long gc = (s.gcEnd == 0 ? gcMs() : s.gcEnd) - s.gcStart;
                long gcN = (s.gcCountEnd == 0 ? gcCount() : s.gcCountEnd) - s.gcCountStart;
                long alloc = (s.allocEnd == 0 ? allocBytes() : s.allocEnd) - s.allocStart;
                double dur = (end - s.startNs) / 1e9;
                w.println(String.format("%-14s %6.1f %6d %5.1f %5d %5d %5d %5d %4d %4d %8d %7d | %6d %4d %4d %4d %4d %4d %4d %4d %11d %6d %9.2f %5d %3d %8d",
                    e.getKey(), dur, s.tasks, 100.0 * s.busyNs / Math.max(1, end - s.startNs), s.t8, s.t16, s.t33, s.t50, s.t100, s.t250, s.jankNs / 1_000_000, s.maxTaskMs, s.beats, s.over16, s.over34, s.over50, s.over100, s.over250, s.over500, s.over1000,
                    s.stalledMs, s.maxMs, cpu / 1e9 / Math.max(dur, 0.001), gc, gcN, alloc / 1_048_576));
            }
        } catch (IOException ignored) {
        }
    }

    static synchronized void writeFrames() {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("frames.txt")))) {
            w.println("phase           frames  fps   p50ms  p95ms  p99ms   maxms  >=16  >=25  >=33  >=50 >=100 | gaps>=33 >=50 >=100 (<1s)");
            for (Map.Entry<String, Stats> e : stats.entrySet()) {
                Stats s = e.getValue();
                java.util.ArrayList<Long> l;
                synchronized (s) { l = new java.util.ArrayList<>(s.frameMs); }
                java.util.Collections.sort(l);
                long end = s.endNs == 0 ? System.nanoTime() : s.endNs;
                double dur = (end - s.startNs) / 1e9;
                java.util.function.IntFunction<Double> pct = q -> l.isEmpty() ? 0.0 : l.get(Math.min(l.size() - 1, (int) Math.floor(l.size() * q / 100.0))) / 10.0;
                w.println(String.format("%-14s %7d %5.1f %6.1f %6.1f %6.1f %7.1f %5d %5d %5d %5d %5d | %8d %4d %5d",
                    e.getKey(), s.frames, s.frames / Math.max(dur, 0.001), pct.apply(50), pct.apply(95), pct.apply(99), l.isEmpty() ? 0.0 : l.get(l.size() - 1) / 10.0,
                    s.f16, s.f25, s.f33, s.f50, s.f100, s.gaps33, s.gaps50, s.gaps100));
            }
        } catch (IOException ignored) {
        }
        // Every frame time (0.1 ms units) per phase, so phases can be pooled for percentiles.
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("frames-raw.txt")))) {
            for (Map.Entry<String, Stats> e : stats.entrySet()) {
                java.util.ArrayList<Long> l, c;
                synchronized (e.getValue()) { l = new java.util.ArrayList<>(e.getValue().frameMs); c = new java.util.ArrayList<>(e.getValue().frameCpu); }
                StringBuilder b = new StringBuilder(e.getKey());
                for (long v : l) b.append(' ').append(v);
                w.println(b);
                // The same frames' UI-thread CPU time, in the same order.
                b = new StringBuilder(e.getKey() + "#cpu");
                for (long v : c) b.append(' ').append(v);
                w.println(b);
            }
        } catch (IOException ignored) {
        }
    }

    static long processCpuNs() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        // A jlinked app runtime may lack jdk.management (com.sun.management): report 0 there.
        try {
            if (os instanceof com.sun.management.OperatingSystemMXBean) return ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
        } catch (LinkageError ignored) {
        }
        return 0;
    }

    static long gcMs() { long t = 0; for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) t += Math.max(0, g.getCollectionTime()); return t; }
    static long gcCount() { long t = 0; for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) t += Math.max(0, g.getCollectionCount()); return t; }

    static long allocBytes() {
        try {
            return allocBytesUnchecked();
        } catch (LinkageError e) {
            return 0;
        }
    }

    static long allocBytesUnchecked() {
        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        if (!(tb instanceof com.sun.management.ThreadMXBean)) return 0;
        com.sun.management.ThreadMXBean st = (com.sun.management.ThreadMXBean) tb;
        long total = 0;
        for (long id : st.getAllThreadIds()) { long b = st.getThreadAllocatedBytes(id); if (b > 0) total += b; }
        return total;
    }
}
