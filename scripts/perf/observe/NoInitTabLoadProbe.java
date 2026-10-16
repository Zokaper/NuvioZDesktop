import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Standalone feasibility probe; never invokes application code or Compose. */
public final class NoInitTabLoadProbe {
    static final class CountingLoader extends URLClassLoader {
        final List<String> loaded = new ArrayList<>();

        CountingLoader(URL[] urls) { super(urls, ClassLoader.getPlatformClassLoader()); }

        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            Class<?> result = super.findClass(name);
            loaded.add(name);
            return result;
        }
    }

    static final class Sentinel {
        static { System.setProperty("perf.noInitSentinel", "initialized"); }
    }

    public static void main(String[] args) throws Exception {
        Class.forName(NoInitTabLoadProbe.class.getName() + "$Sentinel", false,
                NoInitTabLoadProbe.class.getClassLoader());
        if (System.getProperty("perf.noInitSentinel") != null) {
            throw new AssertionError("No-init sentinel initialized");
        }
        List<URL> urls = new ArrayList<>();
        try (var paths = Files.list(Path.of(args[0]))) {
            for (var jar : paths.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                urls.add(jar.toUri().toURL());
            }
        }
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            String[] fields = line.split("\t", 2);
            groups.computeIfAbsent(fields[0], unused -> new ArrayList<>()).add(fields[1]);
        }
        System.out.println("java=" + System.getProperty("java.version") + " jars=" + urls.size());
        var bean = ManagementFactory.getClassLoadingMXBean();
        // Measure only loading. The platform parent has no Nuvio/Kotlin/Compose classes.
        try (var loader = new CountingLoader(urls.toArray(URL[]::new))) {
            for (var group : groups.entrySet()) {
                int failures = 0;
                long before = bean.getTotalLoadedClassCount();
                for (String name : group.getValue()) {
                    try {
                        Class.forName(name, false, loader);
                    } catch (ClassNotFoundException | LinkageError failure) {
                        failures++;
                        System.out.println("failure=" + name + " type=" + failure.getClass().getSimpleName());
                    }
                }
                long loaded = bean.getTotalLoadedClassCount() - before;
                System.out.println(group.getKey() + " requested=" + group.getValue().size()
                        + " newlyLoaded=" + loaded + " failures=" + failures);
                if (failures != 0) throw new AssertionError("Incomplete load");
            }
            long beforeRepeat = bean.getTotalLoadedClassCount();
            for (var group : groups.values()) {
                for (String name : group) Class.forName(name, false, loader);
            }
            System.out.println("repeatNewClasses=" + (bean.getTotalLoadedClassCount() - beforeRepeat));
            System.out.println("loaderDefined=" + loader.loaded.size() + " nuvioDefined="
                    + loader.loaded.stream().filter(name -> name.startsWith("com.nuvio.app.")).count());
        }
        System.out.println("sentinelInitialized=" + (System.getProperty("perf.noInitSentinel") != null));
    }
}
