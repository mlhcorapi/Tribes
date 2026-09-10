package core.game;

/**
 * Deterministic per-thread compute accounting, used as a hardware-independent search budget that
 * captures (close to) ALL of an agent's planning compute - not just forward-model calls.
 *
 * It counts the three dominant operations of an EMCTS-family search:
 *   - advance() : forward-model step
 *   - copy()    : full game-state clone
 *   - eval()    : heuristic state evaluation
 * and combines them into "compute units" via calibrated relative weights, so that 1 unit is a fixed
 * amount of wall-clock time. Because it is a deterministic COUNT (not a clock), a fixed compute-unit
 * budget yields the same search on every run and every machine (reproducible), while still reflecting
 * total planning compute rather than the forward model alone.
 *
 * <h2>Weights are per (configuration, budget level), not global</h2>
 *
 * <p>The three operations do not cost the same for every algorithm or at every budget, and the
 * difference is too large to ignore. A crossover agent splices sequences and repairs them, so the
 * states it advances, copies and evaluates are not the states a mutation-only agent reaches; and at a
 * ten-times-larger budget the tree no longer fits in cache, so every operation costs more than it did
 * at the floor. Pricing all of that with one weight set means a nominal budget of N units buys
 * systematically different amounts of wall-clock per agent and per level - precisely the confound a
 * compute budget exists to remove.
 *
 * <p>A weight is therefore indexed by a PROFILE: {@code <config>@<budgetUnits>}, e.g.
 * {@code EMCTS@40000} or {@code CrossoverCombo_r5@400000}. {@code ComputeCalibrateV6} measures every
 * cell on the target machine and writes them to {@link #WEIGHTS_FILE}; an agent installs its own
 * profile on its own thread at the start of each planning episode ({@link #useProfile}).
 *
 * <h2>What one unit means</h2>
 *
 * <p>All three weights are relative to ONE reference operation: an advance in the reference cell
 * (EMCTS at the smallest budget level) on the calibrated machine. So {@code W_ADVANCE != 1} in
 * general - an advance that genuinely takes 1.4x the reference's time is charged 1.4 units, which is
 * the whole point. Equal unit budgets then mean equal wall-clock across configurations, and the budget
 * levels (1x / 2x / 10x) become proportional in time rather than only in name.
 *
 * <p>With no calibration file present every profile resolves to the compiled defaults
 * (advance=1, copy=0.16, eval=0.077), which is exactly the behaviour from before profiles existed.
 *
 * <p>Thread-local throughout: counts, timings and the active profile are all per-thread, which is what
 * lets one JVM run twelve differently-priced configurations at once.
 */
public final class ComputeMeter {
    private ComputeMeter() {}

    // Calibrated relative op costs (advance = 1.0). Measured on the dev machine by ComputeCalibrate
    // (per-op wall-clock ratios, order ~37us advance / ~6us copy / ~3us eval; the exact weights below
    // are the values ComputeCalibrate printed, not these rounded figures). The resulting shares are
    // shares of the COUNTED ops only and are not comparable with FmComputeProbe's share of total
    // planning wall-clock. Re-run ComputeCalibrate on a new machine and update these.
    /**
     * Where {@code ComputeCalibrateV6} writes this machine's measured weights. Declared BEFORE the
     * weight fields on purpose: static initialisers run in declaration order, and the weights read
     * this path while they are being initialised. Point elsewhere with
     * {@code -Dcompute.weightsFile=<path>}; delete the file to fall back to the compiled defaults.
     */
    private static final String WEIGHTS_FILE = System.getProperty(
            "compute.weightsFile", "test_results/v6/_calibration/compute_weights.properties");

    /** Lazily-read contents of {@link #WEIGHTS_FILE}; empty when there is no such file. */
    private static java.util.Properties fileWeights;

    public static volatile double W_ADVANCE = weight("compute.wAdvance", "COMPUTE_W_ADVANCE", 1.0);
    public static volatile double W_COPY = weight("compute.wCopy", "COMPUTE_W_COPY", 0.16);
    public static volatile double W_EVAL = weight("compute.wEval", "COMPUTE_W_EVAL", 0.077);

    // ------------------------------------------------------------------ per-profile weights

    /**
     * The weight set in force on THIS thread: {@code {advance, copy, eval}}. Defaults to the global
     * weights above, which is what every non-v6 caller - and any agent that never installs a profile -
     * keeps using.
     */
    private static final ThreadLocal<double[]> ACTIVE =
            ThreadLocal.withInitial(() -> new double[]{W_ADVANCE, W_COPY, W_EVAL});

    /** Profile in force on this thread, for provenance in logs. Null means the global weights. */
    private static final ThreadLocal<String> ACTIVE_NAME = new ThreadLocal<>();

    /** Profiles asked for but absent from the calibration file; warned about once each. */
    private static final java.util.Set<String> MISSING =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * Canonical profile key. One place builds it, so the calibrator that WRITES a weight and the agent
     * that READS it cannot drift apart.
     *
     * @param config      configuration name, e.g. {@code EMCTS} or {@code CrossoverCombo}
     * @param ratio       mutation:crossover ratio, or <= 0 for configurations that have none
     * @param budgetUnits the budget level this profile is calibrated at
     */
    public static String profileKey(String config, int ratio, int budgetUnits) {
        return (ratio > 0 ? config + "_r" + ratio : config) + "@" + budgetUnits;
    }

    /**
     * Installs {@code profile}'s weights on the calling thread for the planning episode about to
     * start. Call it from every agent that resets the meter, including with {@code null}: a worker
     * thread is reused across configurations, and a profile left behind by the previous turn would
     * otherwise price this one.
     *
     * <p>An unknown profile falls back to the global weights and says so once. That is deliberate
     * rather than an exception - a smoke test, a probe, or a run at an uncalibrated budget must still
     * work - but it does mean the run banner is where you confirm the profiles you expect are in
     * force.
     */
    public static void useProfile(String profile) {
        double[] w = ACTIVE.get();
        if (profile == null) {
            w[0] = W_ADVANCE; w[1] = W_COPY; w[2] = W_EVAL;
            ACTIVE_NAME.set(null);
            return;
        }
        double a = profileWeight(profile, "wAdvance");
        double c = profileWeight(profile, "wCopy");
        double e = profileWeight(profile, "wEval");
        if (Double.isNaN(a) || Double.isNaN(c) || Double.isNaN(e)) {
            if (MISSING.add(profile))
                System.out.println("[ComputeMeter] no calibrated weights for profile '" + profile
                        + "'; this thread uses the global weights instead");
            w[0] = W_ADVANCE; w[1] = W_COPY; w[2] = W_EVAL;
            ACTIVE_NAME.set(null);
            return;
        }
        w[0] = a; w[1] = c; w[2] = e;
        ACTIVE_NAME.set(profile);
    }

    /** One calibrated weight, or NaN when this profile is not in the file. */
    private static double profileWeight(String profile, String op) {
        String v = fromFile("compute.profile." + profile + "." + op);
        if (v == null || v.trim().isEmpty()) return Double.NaN;
        try {
            double d = Double.parseDouble(v.trim());
            return (d > 0 && d < 1000) ? d : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** True when the calibration file carries a complete weight set for this profile. */
    public static boolean hasProfile(String profile) {
        return profile != null && !Double.isNaN(profileWeight(profile, "wAdvance"))
                && !Double.isNaN(profileWeight(profile, "wCopy"))
                && !Double.isNaN(profileWeight(profile, "wEval"));
    }

    /** Profile in force on this thread, or {@code "global"}. */
    public static String activeProfile() {
        String n = ACTIVE_NAME.get();
        return n == null ? "global" : n;
    }

    /** The weights in force on this thread, as {@code {advance, copy, eval}}. */
    public static double[] activeWeights() {
        double[] w = ACTIVE.get();
        return new double[]{w[0], w[1], w[2]};
    }

    /** How many profiles the calibration file defines; 0 means the pipeline is running unprofiled. */
    public static int profileCount() {
        return profileNames().size();
    }

    /** Every profile the calibration file defines, sorted. */
    public static java.util.List<String> profileNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String pre = "compute.profile.", suf = ".wAdvance";
        for (String k : fileKeys())
            if (k.startsWith(pre) && k.endsWith(suf))
                out.add(k.substring(pre.length(), k.length() - suf.length()));
        java.util.Collections.sort(out);
        return out;
    }

    /** One line describing a calibrated profile, for a banner. */
    public static String profileSummary(String profile) {
        double a = profileWeight(profile, "wAdvance");
        double c = profileWeight(profile, "wCopy");
        double e = profileWeight(profile, "wEval");
        if (Double.isNaN(a)) return profile + ": (not calibrated)";
        return String.format(java.util.Locale.ROOT, "%-26s advance=%.4f copy=%.4f eval=%.4f",
                profile, a, c, e);
    }

    /**
     * The weights are wall-clock ratios and therefore a property of the MACHINE, not of the code, but
     * the ones compiled into this file came from whichever machine last ran a calibration. Resolution
     * order, most specific first:
     *
     * <ol>
     *   <li>{@code -Dcompute.wCopy} / {@code -Dcompute.wEval} (or {@code COMPUTE_W_COPY} /
     *       {@code COMPUTE_W_EVAL} in the environment) — an explicit override for one run;</li>
     *   <li>{@link #WEIGHTS_FILE}, written by {@code ComputeCalibrateV6} after a calibration that
     *       passed its own validity checks — this is the normal path, and it needs no action from the
     *       operator beyond running the calibration once;</li>
     *   <li>the compiled defaults.</li>
     * </ol>
     *
     * <p>Whichever wins is printed once and reported in the run banner, because a compute-unit is only
     * defined relative to these numbers: a dataset is not interpretable without knowing which weights
     * priced it.
     *
     * <p>Changing them changes what a compute-unit IS, so any budget expressed in units has to be
     * re-derived afterwards. Never change them mid-experiment: all configs must be priced in the same
     * currency for the comparison to hold.
     */
    private static double weight(String sysProp, String envVar, double dflt) {
        String v = System.getProperty(sysProp);
        String source = "-D" + sysProp;
        if (v == null || v.trim().isEmpty()) { v = System.getenv(envVar); source = "$" + envVar; }
        if (v == null || v.trim().isEmpty()) { v = fromFile(sysProp); source = WEIGHTS_FILE; }
        if (v == null || v.trim().isEmpty()) return dflt;
        try {
            double d = Double.parseDouble(v.trim());
            if (d > 0 && d < 100) {
                System.out.println("[ComputeMeter] " + sysProp + " = " + d + " (from " + source
                        + "; compiled default was " + dflt + ")");
                return d;
            }
            System.err.println("[ComputeMeter] ignoring implausible " + sysProp + "=" + v
                    + " from " + source);
        } catch (NumberFormatException e) {
            System.err.println("[ComputeMeter] ignoring unparseable " + sysProp + "=" + v
                    + " from " + source);
        }
        return dflt;
    }

    /** One property from the calibration file, or null if the file is absent or unreadable. */
    private static synchronized String fromFile(String key) {
        if (fileWeights == null) {
            fileWeights = new java.util.Properties();
            java.io.File f = new java.io.File(WEIGHTS_FILE);
            if (f.isFile()) {
                try (java.io.InputStream in = new java.io.FileInputStream(f)) {
                    fileWeights.load(in);
                } catch (Exception e) {
                    System.err.println("[ComputeMeter] could not read " + WEIGHTS_FILE + ": " + e);
                }
            }
        }
        return fileWeights.getProperty(key);
    }

    /** Keys present in the calibration file; empty when there is no file. */
    private static synchronized java.util.Set<String> fileKeys() {
        fromFile("");                       // forces the lazy load
        return fileWeights.stringPropertyNames();
    }

    /**
     * One-line provenance string for run logs and output headers: the GLOBAL fallback weights, plus
     * how many per-profile sets are loaded. A compute-unit is only defined relative to these, so a
     * dataset is not interpretable without them.
     */
    public static String weightsSummary() {
        int n = profileCount();
        return String.format(java.util.Locale.ROOT, "advance=%.4f copy=%.4f eval=%.4f%s",
                W_ADVANCE, W_COPY, W_EVAL,
                n > 0 ? "  (+" + n + " calibrated per-config profiles)" : "  (no per-config profiles)");
    }

    // Opt-in nanosecond profiling for calibration only (default OFF => just cheap counting).
    public static volatile boolean PROFILE = false;

    private static final ThreadLocal<long[]> COUNTS = ThreadLocal.withInitial(() -> new long[3]); // [adv, copy, eval]
    private static final ThreadLocal<long[]> NANOS  = ThreadLocal.withInitial(() -> new long[3]); // [adv, copy, eval]

    public static void onAdvance() { COUNTS.get()[0]++; }
    public static void onCopy()    { COUNTS.get()[1]++; }
    public static void onEval()    { COUNTS.get()[2]++; }

    public static void addAdvanceNanos(long n) { NANOS.get()[0] += n; }
    public static void addCopyNanos(long n)    { NANOS.get()[1] += n; }
    public static void addEvalNanos(long n)    { NANOS.get()[2] += n; }

    /** Reset the per-turn counts (call at the start of each planning episode). */
    public static void reset() { long[] c = COUNTS.get(); c[0] = c[1] = c[2] = 0; }
    public static void resetNanos() { long[] n = NANOS.get(); n[0] = n[1] = n[2] = 0; }

    public static long advances() { return COUNTS.get()[0]; }
    public static long copies()   { return COUNTS.get()[1]; }
    public static long evals()    { return COUNTS.get()[2]; }
    public static long advanceNanos() { return NANOS.get()[0]; }
    public static long copyNanos()    { return NANOS.get()[1]; }
    public static long evalNanos()    { return NANOS.get()[2]; }

    /**
     * Weighted compute units used on the current thread since the last reset(), priced by the profile
     * in force on this thread ({@link #useProfile}).
     *
     * <p>Two thread-local reads rather than one - which is why this is called once per search
     * iteration, to test the ceiling, and never once per operation.
     */
    public static double computeUnits() {
        long[] c = COUNTS.get();
        double[] w = ACTIVE.get();
        return c[0] * w[0] + c[1] * w[1] + c[2] * w[2];
    }
}
