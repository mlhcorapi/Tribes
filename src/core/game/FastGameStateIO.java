package core.game;

import java.io.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Fast binary save/load for {@link GameState} via Java native object serialization (replacing the
 * slow per-turn JSON path in {@link GameSaver}/{@link GameLoader} for use cases like the v4
 * isolation-phase portfolio).
 *
 * <p>Performance: native {@code ObjectOutputStream} is typically 5–10× faster than the JSON path for
 * Tribes' board sizes, and the gzipped output is ~30% the size of the equivalent JSON directory.
 *
 * <p>Action caches are intentionally not serialised: {@link GameState#cityActions},
 * {@link GameState#unitActions}, {@link GameState#tribeActions} are marked {@code transient} and
 * rebuilt by {@code GameState.readObject(...)} via {@link GameState#computePlayerActions} — the same
 * recompute-on-load pattern {@link GameLoader} already uses, so the 30+ {@link core.actions.Action}
 * subclasses don't need to implement {@link Serializable}.
 *
 * <p>The on-disk format is plain Java serialisation wrapped in GZIP. It is intended for transient
 * scratch use (portfolio caches) — re-generate the portfolio after refactors that change the engine
 * class shape rather than relying on long-term backward compatibility.
 */
public final class FastGameStateIO {
    private FastGameStateIO() {}

    /** Write a single {@link GameState} to {@code file} (parent dirs created if missing). */
    public static void save(GameState gs, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (ObjectOutputStream out = new ObjectOutputStream(
                new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))))) {
            out.writeObject(gs);
        }
    }

    /** Read a single {@link GameState} previously written with {@link #save}. */
    public static GameState load(File file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(
                new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))))) {
            return (GameState) in.readObject();
        }
    }
}
