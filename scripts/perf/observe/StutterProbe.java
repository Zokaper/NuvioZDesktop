import java.awt.AWTEvent;
import java.awt.DisplayMode;
import java.awt.EventQueue;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.InvocationEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Passive stutter probe for an installed, unmodified Nuvio Z desktop app, driven by a human.
 *
 * Injected with JAVA_TOOL_OPTIONS=-javaagent:stutterprobe.jar=<outdir>, so the app runs from its own
 * jpackage launcher on its own (JFR-less) JDK 17 runtime, with the real profile, session and data.
 * It never posts input and never touches preferences or app state.
 *
 * Compose Desktop composes, lays out and draws on the AWT event thread, so every visible stutter is
 * that thread being late. The probe records, in timeline.tsv (times in microseconds since premain):
 *
 *   T seq startUs durUs kind classes desc   every frame, every task >= 1 ms
 *   S seq offUs id,id,id...                 EDT stack while a task runs past the threshold
 *   D id frame                              stack-frame dictionary for S lines
 *   I us what x y extra                     user input (press, release, wheel, key)
 *   M us USER-MARK                          F8: "that was a stutter"
 *   G us gcName countDelta msDelta          garbage collections
 *   K us heapMB classes jitMs threads tasks frames   once a second
 *   SHOT us startUs endUs file              a screenshot ~1.2 s after the last click
 *   Q us ...                                queue installed / environment
 *
 * kind: F = skiko frame (composition + layout + draw), C = coroutine resumed on Dispatchers.Main,
 * R = other runnable, E = AWT event (input, paint, focus, window).
 */
public final class StutterProbe {
    static final long T0 = System.nanoTime();
    static final long EPOCH0 = System.currentTimeMillis();
    static Path dir;
    static final ConcurrentLinkedQueue<String> out = new ConcurrentLinkedQueue<>();

    static volatile Thread edt;
    static volatile long curStart;     // nanoTime the current EDT task began, 0 when idle
    static volatile long curSeq;
    static volatile boolean queueLive; // the TimingQueue has dispatched at least one event
    static volatile long lastInputNs;

    static long sampleThresholdNs = 8_000_000L;
    static long samplePeriodMs = 3;
    static boolean shots = true;

    static final ClassLoadingMXBean CL = ManagementFactory.getClassLoadingMXBean();
    static final CompilationMXBean JIT = ManagementFactory.getCompilationMXBean();
    static final MemoryMXBean MEM = ManagementFactory.getMemoryMXBean();

    static long us() { return (System.nanoTime() - T0) / 1000L; }
    static long us(long nano) { return (nano - T0) / 1000L; }

    public static void premain(String args, Instrumentation inst) {
        try {
            openRunnableField(inst);
            start(args);
        } catch (Throwable t) {
            System.err.println("StutterProbe failed to start: " + t);
        }
    }

    static void start(String args) throws IOException {
        String outDir = null;
        if (args != null) {
            for (String part : args.split(",")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 1) outDir = kv[0];
                else if (kv[0].equals("dir")) outDir = kv[1];
                else if (kv[0].equals("shots")) shots = !kv[1].equals("0");
                else if (kv[0].equals("thresholdMs")) sampleThresholdNs = Long.parseLong(kv[1]) * 1_000_000L;
                else if (kv[0].equals("periodMs")) samplePeriodMs = Long.parseLong(kv[1]);
            }
        }
        if (outDir == null) outDir = System.getProperty("java.io.tmpdir") + File.separator + "stutterprobe";
        dir = Paths.get(outDir);
        Files.createDirectories(dir.resolve("shots"));
        emit("Q " + us() + " premain epochMs=" + EPOCH0 + " java=" + System.getProperty("java.version")
            + " vm=" + System.getProperty("java.vm.name") + " app=" + System.getProperty("jpackage.app-version")
            + " cpus=" + Runtime.getRuntime().availableProcessors() + " maxHeapMB=" + Runtime.getRuntime().maxMemory() / 1_048_576
            + " jvmStartEpochMs=" + ManagementFactory.getRuntimeMXBean().getStartTime()
            + " processStart=" + ProcessHandle.current().info().startInstant().map(Object::toString).orElse("?")
            + " pid=" + ProcessHandle.current().pid()
            + " prefs=" + System.getProperty("java.util.prefs.PreferencesFactory", "default"));

        daemon("probe-writer", Thread.NORM_PRIORITY, StutterProbe::writer);
        daemon("probe-attach", Thread.MAX_PRIORITY, StutterProbe::attach);
        daemon("probe-sampler", Thread.MAX_PRIORITY, StutterProbe::sampler);
        daemon("probe-counters", Thread.NORM_PRIORITY, StutterProbe::counters);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            emit("Q " + us() + " shutdown");
            drain();
        }, "probe-shutdown"));
    }

    interface Body { void run() throws Exception; }

    static void daemon(String name, int priority, Body body) {
        Thread t = new Thread(() -> {
            try { body.run(); } catch (Throwable e) { emit("Q " + us() + " " + name + " died " + e); }
        }, name);
        t.setDaemon(true);
        t.setPriority(priority);
        t.start();
    }

    static void emit(String line) { out.add(line); }

    /**
     * For smoke runs on a throwaway data directory only:
     * -Djava.util.prefs.PreferencesFactory=StutterProbe$MemoryPrefsFactory keeps the app off
     * HKCU\Software\JavaSoft\Prefs, where the official login shared by every Nuvio build lives.
     * A real measurement run leaves prefs alone, so the app is signed in exactly as usual.
     */
    public static final class MemoryPrefsFactory implements java.util.prefs.PreferencesFactory {
        private static final java.util.prefs.Preferences USER = new MemoryPrefs(null, "");
        private static final java.util.prefs.Preferences SYSTEM = new MemoryPrefs(null, "");
        public java.util.prefs.Preferences userRoot() { return USER; }
        public java.util.prefs.Preferences systemRoot() { return SYSTEM; }
    }

    static final class MemoryPrefs extends java.util.prefs.AbstractPreferences {
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

    // ---------------------------------------------------------------- writer

    static BufferedWriter writer;

    static void writer() {
        try {
            writer = Files.newBufferedWriter(dir.resolve("timeline.tsv"), StandardCharsets.UTF_8);
            while (true) {
                Thread.sleep(250);
                drain();
            }
        } catch (Exception e) {
            System.err.println("StutterProbe writer died: " + e);
        }
    }

    static synchronized void drain() {
        if (writer == null) return;
        try {
            String s;
            while ((s = out.poll()) != null) { writer.write(s); writer.write('\n'); }
            writer.flush();
        } catch (IOException ignored) {
        }
    }

    // ---------------------------------------------------------------- attaching to the EDT

    /**
     * Waits for the app to start AWT itself, then wraps its event queue. Touching Toolkit here first
     * would initialise AWT before the app's main() sets its renderer and Swing properties.
     */
    static void attach() throws Exception {
        while (true) {
            Thread found = findThread("AWT-EventQueue");
            if (found != null) {
                edt = found;
                break;
            }
            Thread.sleep(1);
        }
        emit("Q " + us() + " edt-seen " + edt.getName());
        Toolkit.getDefaultToolkit().getSystemEventQueue().push(new TimingQueue(null));
        emit("Q " + us() + " queue-pushed");
        startQueueGuard();
        // Environment, once the main window exists.
        for (int i = 0; i < 600; i++) {
            Thread.sleep(100);
            Window w = mainWindow();
            if (w == null) continue;
            StringBuilder b = new StringBuilder("Q " + us() + " env renderApi=" + System.getProperty("skiko.renderApi")
                + " uiScale=" + System.getProperty("sun.java2d.uiScale") + " window=" + w.getWidth() + "x" + w.getHeight());
            try {
                for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    DisplayMode m = d.getDisplayMode();
                    b.append(" screen=").append(m.getWidth()).append('x').append(m.getHeight()).append('@').append(m.getRefreshRate())
                        .append("Hz scale=").append(d.getDefaultConfiguration().getDefaultTransform().getScaleX());
                }
            } catch (Throwable ignored) {
            }
            emit(b.toString());
            scheduleShot(); // a reference picture of the first screen
            break;
        }
    }

    static Thread findThread(String prefix) {
        ThreadGroup g = Thread.currentThread().getThreadGroup();
        while (g.getParent() != null) g = g.getParent();
        Thread[] all = new Thread[g.activeCount() + 16];
        int n = g.enumerate(all, true);
        for (int i = 0; i < n; i++) if (all[i] != null && all[i].getName().startsWith(prefix)) return all[i];
        return null;
    }

    static Window mainWindow() {
        Window best = null;
        for (Window w : Window.getWindows()) {
            if (!w.isShowing()) continue;
            if (best == null || w.getWidth() * w.getHeight() > best.getWidth() * best.getHeight()) best = w;
        }
        return best;
    }

    // ---------------------------------------------------------------- the timed queue

    static Field RUNNABLE;
    static Field continuationField;
    static volatile boolean describeFailed;

    /** InvocationEvent.runnable names the work; java.awt.event is opened to the probe alone. */
    static void openRunnableField(Instrumentation inst) {
        try {
            Module desktop = InvocationEvent.class.getModule();
            inst.redefineModule(desktop, java.util.Set.of(), java.util.Map.of(),
                java.util.Map.of("java.awt.event", java.util.Set.of(StutterProbe.class.getModule())),
                java.util.Set.of(), java.util.Map.of());
            Field f = InvocationEvent.class.getDeclaredField("runnable");
            f.setAccessible(true);
            RUNNABLE = f;
        } catch (Throwable t) {
            emit("Q " + us() + " runnable field unavailable " + t);
        }
    }

    static final Map<Class<?>, String> nameCache = new ConcurrentHashMap<>();
    static long tasksTotal, framesTotal;

    /** What ran: the runnable's class, or for a coroutine the suspended lambda it resumes. */
    static String describe(InvocationEvent e) {
        if (RUNNABLE == null) return "?";
        try {
            Object r = RUNNABLE.get(e);
            if (r == null) return "null";
            Class<?> cls = r.getClass();
            if (cls.getName().equals("kotlinx.coroutines.internal.DispatchedContinuation")) {
                if (continuationField == null) {
                    continuationField = cls.getField("continuation");
                }
                Object c = continuationField.get(r);
                if (c == null) return "C:?";
                return nameCache.computeIfAbsent(c.getClass(), k -> "C:" + k.getName());
            }
            return nameCache.computeIfAbsent(cls, k -> "R:" + k.getName());
        } catch (Throwable t) {
            if (!describeFailed) { describeFailed = true; emit("Q " + us() + " describe failed " + t); }
            return "?";
        }
    }

    /**
     * The app may push an EventQueue of its own later (wheel smoothing on Windows, button mapping on
     * Linux). Only the topmost queue dispatches, so the probe would go blind; re-push on top of it
     * and hand each event to that queue's own dispatchEvent.
     */
    static void startQueueGuard() {
        Thread t = new Thread(() -> {
            try {
                Field next = EventQueue.class.getDeclaredField("nextQueue");
                next.setAccessible(true);
                java.lang.reflect.Method dispatch = EventQueue.class.getDeclaredMethod("dispatchEvent", AWTEvent.class);
                dispatch.setAccessible(true);
                DISPATCH = dispatch;
                while (true) {
                    Thread.sleep(200);
                    EventQueue top = Toolkit.getDefaultToolkit().getSystemEventQueue();
                    for (Object n = next.get(top); n != null; n = next.get(top)) top = (EventQueue) n;
                    if (!(top instanceof TimingQueue)) {
                        top.push(new TimingQueue(top));
                        emit("Q " + us() + " queue-repushed over " + top.getClass().getName());
                    }
                }
            } catch (Throwable ex) {
                emit("Q " + us() + " queue-guard-failed " + ex);
            }
        }, "stutterprobe-queue-guard");
        t.setDaemon(true);
        t.start();
    }

    static volatile java.lang.reflect.Method DISPATCH;

    static final class TimingQueue extends EventQueue {
        long seq;
        final EventQueue delegate;

        TimingQueue(EventQueue delegate) {
            this.delegate = delegate;
        }

        void dispatchThrough(AWTEvent e) {
            if (delegate == null) {
                super.dispatchEvent(e);
                return;
            }
            try {
                DISPATCH.invoke(delegate, e);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                Throwable c = ex.getCause();
                if (c instanceof RuntimeException) throw (RuntimeException) c;
                if (c instanceof Error) throw (Error) c;
                throw new RuntimeException(c);
            } catch (IllegalAccessException ex) {
                super.dispatchEvent(e);
            }
        }

        @Override
        protected void dispatchEvent(AWTEvent e) {
            if (!queueLive) { queueLive = true; edt = Thread.currentThread(); }
            long s = ++seq;
            long classes0 = CL.getTotalLoadedClassCount();
            long t0 = System.nanoTime();
            curSeq = s;
            curStart = t0;
            if (e instanceof MouseEvent || e instanceof KeyEvent) input(e, t0);
            try {
                dispatchThrough(e);
            } finally {
                long t1 = System.nanoTime();
                curStart = 0;
                long d = t1 - t0;
                tasksTotal++;
                char kind;
                String desc = null;
                if (e instanceof InvocationEvent) {
                    desc = describe((InvocationEvent) e);
                    if (desc.contains("skiko.FrameDispatcher") || desc.contains("skiko.SkiaLayer") && desc.contains("redraw")) kind = 'F';
                    else kind = desc.startsWith("C:") ? 'C' : 'R';
                } else {
                    kind = 'E';
                }
                if (kind == 'F') framesTotal++;
                if (kind == 'F' || d >= 1_000_000L) {
                    long classes = CL.getTotalLoadedClassCount() - classes0;
                    StringBuilder b = new StringBuilder(96);
                    b.append("T ").append(s).append(' ').append(us(t0)).append(' ').append(d / 1000L).append(' ').append(kind)
                        .append(' ').append(classes).append(' ');
                    if (kind == 'E') b.append(eventName(e));
                    else b.append(desc);
                    emit(b.toString());
                }
            }
        }
    }

    static String eventName(AWTEvent e) {
        String n = e.getClass().getSimpleName();
        return n + "#" + e.getID();
    }

    static void input(AWTEvent e, long t) {
        int id = e.getID();
        if (e instanceof MouseWheelEvent) {
            MouseWheelEvent w = (MouseWheelEvent) e;
            // Raw toolkit events are "wheel"; events the app synthesises (smoothing) are "swheel".
            String what = w.getClass() == MouseWheelEvent.class ? "wheel" : "swheel";
            emit("I " + us(t) + " " + what + " " + w.getX() + " " + w.getY() + " " + w.getWheelRotation() + (w.isShiftDown() ? "s" : "")
                + " " + w.getPreciseWheelRotation() + " " + w.getWhen() + " " + w.getComponent().getClass().getName());
            lastInputNs = t;
        } else if (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED) {
            MouseEvent m = (MouseEvent) e;
            emit("I " + us(t) + " " + (id == MouseEvent.MOUSE_PRESSED ? "press" : "release") + " " + m.getX() + " " + m.getY()
                + " b" + m.getButton() + " " + m.getComponent().getClass().getSimpleName());
            lastInputNs = t;
            if (id == MouseEvent.MOUSE_RELEASED) scheduleShot();
        } else if (id == KeyEvent.KEY_PRESSED) {
            KeyEvent k = (KeyEvent) e;
            if (k.getKeyCode() == KeyEvent.VK_F8) {
                emit("M " + us(t) + " USER-MARK");
            } else {
                emit("I " + us(t) + " key " + k.getKeyCode() + " 0 " + KeyEvent.getKeyText(k.getKeyCode()).replace(' ', '_'));
                scheduleShot();
            }
            lastInputNs = t;
        }
    }

    // ---------------------------------------------------------------- stack sampler

    static final Map<String, Integer> dict = new HashMap<>();

    static int frameId(StackTraceElement f) {
        String key = f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber();
        Integer id = dict.get(key);
        if (id == null) {
            id = dict.size();
            dict.put(key, id);
            emit("D " + id + " " + key);
        }
        return id;
    }

    static void sampler() throws InterruptedException {
        long preQueueUntil = System.nanoTime() + 60_000_000_000L;
        while (true) {
            Thread.sleep(samplePeriodMs);
            Thread t = edt;
            if (t == null) continue;
            long seq, start, now = System.nanoTime();
            if (!queueLive) {
                // Before the wrapped queue takes over (the window-creation task usually): sample blind.
                if (now > preQueueUntil) continue;
                seq = -1;
                start = T0;
            } else {
                start = curStart;
                seq = curSeq;
                if (start == 0 || now - start < sampleThresholdNs) continue;
            }
            StackTraceElement[] st = t.getStackTrace();
            if (queueLive && seq >= 0 && (curSeq != seq || curStart == 0)) continue; // the task ended meanwhile
            if (st.length == 0) continue;
            StringBuilder b = new StringBuilder(st.length * 5 + 32);
            b.append("S ").append(seq).append(' ').append((now - start) / 1000L).append(' ');
            int n = Math.min(st.length, 220);
            for (int i = 0; i < n; i++) {
                if (i > 0) b.append(',');
                b.append(frameId(st[i]));
            }
            if (seq < 0) b.append(" @").append(us(now));
            emit(b.toString());
        }
    }

    // ---------------------------------------------------------------- counters, GC

    static void counters() throws InterruptedException {
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        long[] counts = new long[gcs.size()], times = new long[gcs.size()];
        long nextK = 0;
        while (true) {
            Thread.sleep(50);
            for (int i = 0; i < gcs.size(); i++) {
                GarbageCollectorMXBean g = gcs.get(i);
                long c = g.getCollectionCount(), tm = g.getCollectionTime();
                if (c != counts[i]) {
                    if (counts[i] != 0 || times[i] != 0 || c > 0) {
                        emit("G " + us() + " " + g.getName().replace(' ', '_') + " " + (c - counts[i]) + " " + (tm - times[i]));
                    }
                    counts[i] = c;
                    times[i] = tm;
                }
            }
            long now = us();
            if (now >= nextK) {
                nextK = now + 1_000_000L;
                emit("K " + now + " " + MEM.getHeapMemoryUsage().getUsed() / 1_048_576 + " " + CL.getTotalLoadedClassCount()
                    + " " + (JIT.isCompilationTimeMonitoringSupported() ? JIT.getTotalCompilationTime() : -1)
                    + " " + ManagementFactory.getThreadMXBean().getThreadCount() + " " + tasksTotal + " " + framesTotal);
            }
        }
    }

    // ---------------------------------------------------------------- screenshots

    static ScheduledExecutorService shooter;
    static ScheduledFuture<?> pendingShot;
    static Robot robot;
    static int shotNo;

    static synchronized void scheduleShot() {
        if (!shots) return;
        if (shooter == null) {
            shooter = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "probe-shot");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
        }
        if (pendingShot != null) pendingShot.cancel(false);
        pendingShot = shooter.schedule(StutterProbe::shoot, 1200, TimeUnit.MILLISECONDS);
    }

    /** Where the user landed, ~1.2 s after the last click or key, small and off the event thread. */
    static void shoot() {
        try {
            Window w = mainWindow();
            if (w == null) return;
            if (robot == null) robot = new Robot();
            long a = us();
            Rectangle r = w.getBounds();
            BufferedImage img = robot.createScreenCapture(r);
            int tw = 640, th = Math.max(1, img.getHeight() * tw / Math.max(1, img.getWidth()));
            BufferedImage small = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = small.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, tw, th, null);
            g.dispose();
            String name = String.format("shot-%04d-%08d.jpg", ++shotNo, a / 1000);
            javax.imageio.ImageIO.write(small, "jpg", dir.resolve("shots").resolve(name).toFile());
            emit("SHOT " + us() + " " + a + " " + us() + " " + name);
        } catch (Throwable t) {
            emit("Q " + us() + " shot failed " + t);
        }
    }
}
