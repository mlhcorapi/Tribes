import core.Constants;
import core.Types;
import core.game.Game;
import gui.GUI;
import gui.WindowInput;
import org.json.JSONArray;
import org.json.JSONObject;
import players.ActionController;
import players.Agent;
import players.KeyController;
import utils.file.IO;

import java.util.ArrayList;
import java.util.Random;

import static core.Types.GAME_MODE.*;

/**
 * Entry point for recording a "followable" example game.
 *
 * Compared to {@link Play}, this runner is built for watching/recording: visuals are always on, the
 * actions of each turn are executed one at a time with a configurable pause between them, there is a
 * (longer) configurable pause between players' turns, and the window opens already showing the map,
 * the scores and the tech tree but waits for the user to press a "Start" button before the game
 * actually begins.
 *
 * All configuration (pauses, algorithms and tribes) is read from {@code followable_game.json}.
 */
public class FollowableGame {

    private static boolean RUN_VERBOSE = true;
    private static long AGENT_SEED = -1;
    private static long GAME_SEED = -1;

    public static void main(String[] args) {

        try {
            // Resolve the config file robustly: VS Code / IDE "Run" launches with a different working
            // directory than the project root, so a plain relative path often isn't found.
            String configPath = resolveConfigPath(args);
            if (configPath == null) return;  // resolveConfigPath already printed a helpful message

            // The framework loads resources (terrainProbs.json, levels/, img/, save/) relative to the
            // working directory. If we were launched from a different folder (common with IDE "Run"
            // buttons), re-launch in the folder that holds the config so everything resolves.
            if (maybeRelaunchInProjectRoot(configPath)) return;

            JSONObject config = new IO().readJSON(configPath);
            if (config == null || config.isEmpty()) {
                System.out.println("ERROR: 'followable_game.json' is empty or not valid JSON: " + configPath);
                return;
            }
            System.out.println("Loaded config: " + configPath);

            String runMode = config.getString("Run Mode");
            Constants.VERBOSE = config.getBoolean("Verbose");
            Constants.VISUALS = true;  // Followable mode is always visual.

            // Pacing for the followable playback.
            int actionPauseMs = config.getInt("Action Pause Millis");
            long turnPauseMs = config.getLong("Turn Pause Millis");
            Constants.FRAME_DELAY = actionPauseMs;  // delay (ms) between consecutive actions

            JSONArray playersArray = (JSONArray) config.get("Players");
            JSONArray tribesArray = (JSONArray) config.get("Tribes");
            if (playersArray.length() != tribesArray.length())
                throw new Exception("Number of players must be equal to number of tribes");

            int nPlayers = playersArray.length();
            Run.PlayerType[] playerTypes = new Run.PlayerType[nPlayers];
            Types.TRIBE[] tribes = new Types.TRIBE[nPlayers];

            for (int i = 0; i < nPlayers; ++i) {
                playerTypes[i] = Run.parsePlayerTypeStr(playersArray.getString(i));
                tribes[i] = Run.parseTribeStr(tribesArray.getString(i));
            }

            Types.GAME_MODE gameMode = config.getString("Game Mode").equalsIgnoreCase("Capitals") ?
                    CAPITALS : SCORE;

            Run.MAX_LENGTH = config.getInt("Search Depth");
            Run.FORCE_TURN_END = config.getBoolean("Force End");
            Run.MCTS_ROLLOUTS = config.getBoolean("Rollouts");
            // TBETS rollouts are a separate, much more expensive thing than MCTS's
            // (a whole scripted TURN per node, not a bounded action rollout), so they get
            // their own optional key and stay off unless a config asks for them.
            Run.TBETS_ROLLOUTS = config.optBoolean("TBETS Rollouts", false);
            Run.POP_SIZE = config.getInt("Population Size");

            //Portfolio and pruning variables:
            Run.PRUNING = config.getBoolean("Pruning");
            Run.PROGBIAS = config.getBoolean("Progressive Bias");
            Run.K_INIT_MULT = config.getDouble("K init mult");
            Run.T_MULT = config.getDouble("T mult");
            Run.A_MULT = config.getDouble("A mult");
            Run.B = config.getDouble("B");

            JSONArray weights = null;
            if (config.has("pMCTS Weights"))
                weights = (JSONArray) config.get("pMCTS Weights");
            Run.pMCTSweights = Run.getWeights(weights);

            if (config.has("Remove Fishing"))
                Constants.REMOVE_FISHING = config.getBoolean("Remove Fishing");

            AGENT_SEED = config.getLong("Agents Seed");
            GAME_SEED = config.getLong("Game Seed");
            long levelSeed = config.getLong("Level Seed");

            Game game;
            //1. One game with visuals using the Level Generator:
            if (runMode.equalsIgnoreCase("PlayLG")) {
                game = _prepareGame(tribes, levelSeed, playerTypes, gameMode);

            //2. One game with visuals from a level file:
            } else if (runMode.equalsIgnoreCase("PlayFile")) {
                String levelFile = config.getString("Level File");
                game = _prepareGame(levelFile, playerTypes, gameMode);

            //3. One game with visuals from a savegame:
            } else if (runMode.equalsIgnoreCase("Replay")) {
                String saveGameFile = config.getString("Replay File Name");
                game = _loadGame(playerTypes, saveGameFile);

            } else {
                System.out.println("ERROR: run mode '" + runMode + "' not recognized.");
                return;
            }

            runFollowable(game, turnPauseMs);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Finds followable_game.json without depending on the working directory (IDE "Run" buttons often
     * launch from a different folder than the project root). Search order:
     *   1. an explicit path passed as the first command-line argument;
     *   2. the current working directory and up to 5 parent directories (also checking a
     *      "Tribes-master" subfolder of each);
     *   3. the folder containing the compiled classes / jar and its parents (same checks).
     * @return absolute path to the config file, or null if it could not be found (a diagnostic is
     *         printed in that case).
     */
    private static String resolveConfigPath(String[] args) {
        final String name = "followable_game.json";
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();

        if (args != null && args.length > 0 && args[0] != null && !args[0].trim().isEmpty())
            candidates.add(args[0].trim());

        candidates.add(name);  // plain relative to the working directory

        // Walk up from the working directory.
        try {
            java.io.File dir = new java.io.File(".").getCanonicalFile();
            for (int i = 0; i < 5 && dir != null; i++) {
                candidates.add(new java.io.File(dir, name).getPath());
                candidates.add(new java.io.File(new java.io.File(dir, "Tribes-master"), name).getPath());
                dir = dir.getParentFile();
            }
        } catch (Exception ignored) { }

        // Walk up from the location of the compiled classes / jar (e.g. bin/, out/, target/).
        try {
            java.io.File src = new java.io.File(
                    FollowableGame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            java.io.File dir = src.isFile() ? src.getParentFile() : src;
            for (int i = 0; i < 6 && dir != null; i++) {
                candidates.add(new java.io.File(dir, name).getPath());
                candidates.add(new java.io.File(new java.io.File(dir, "Tribes-master"), name).getPath());
                dir = dir.getParentFile();
            }
        } catch (Exception ignored) { }

        for (String c : candidates) {
            java.io.File f = new java.io.File(c);
            if (f.isFile()) return f.getAbsolutePath();
        }

        System.out.println("ERROR: Couldn't find '" + name + "'.");
        System.out.println("Working directory (user.dir): " + System.getProperty("user.dir"));
        System.out.println("Looked in:");
        for (String c : candidates)
            System.out.println("  - " + new java.io.File(c).getAbsolutePath());
        System.out.println("Tip: pass the path as the first argument, e.g.  java FollowableGame path/to/" + name);
        return null;
    }

    /**
     * If the working directory is not the project root (the folder that contains the config and the
     * other resources such as terrainProbs.json), re-launches this program in that folder. This makes
     * the script work from IDE "Run" buttons, which often launch from the workspace root.
     * @return true if a child process was launched (the caller should then return), false otherwise.
     */
    private static boolean maybeRelaunchInProjectRoot(String configPath) {
        try {
            if (System.getenv("FOLLOWABLE_RELAUNCHED") != null) return false;  // already a relaunch

            java.io.File projectRoot = new java.io.File(configPath).getCanonicalFile().getParentFile();
            java.io.File cwd = new java.io.File(".").getCanonicalFile();
            if (projectRoot == null || projectRoot.equals(cwd)) return false;

            // Only relaunch if the resources really live in projectRoot and not in the current cwd.
            if (!new java.io.File(projectRoot, "terrainProbs.json").isFile()) return false;
            if (new java.io.File(cwd, "terrainProbs.json").isFile()) return false;

            System.out.println("Working directory is '" + cwd + "',");
            System.out.println("but the game resources live in '" + projectRoot + "'. Re-launching there...");

            String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin"
                    + java.io.File.separator + "java";
            String sep = System.getProperty("path.separator");
            StringBuilder cp = new StringBuilder();
            for (String part : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(sep))) {
                if (cp.length() > 0) cp.append(sep);
                cp.append(new java.io.File(part).getAbsolutePath());  // make absolute before cwd change
            }

            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", cp.toString(),
                    "FollowableGame", new java.io.File(configPath).getAbsolutePath());
            pb.directory(projectRoot);
            pb.inheritIO();
            pb.environment().put("FOLLOWABLE_RELAUNCHED", "1");
            int code = pb.start().waitFor();
            System.exit(code);
            return true;  // unreachable
        } catch (Exception e) {
            System.out.println("WARNING: could not re-launch in the project root (" + e + ").");
            System.out.println("Please run FollowableGame with the working directory set to the folder "
                    + "containing followable_game.json.");
            return false;  // fall through and try in-process anyway
        }
    }

    private static void runFollowable(Game game, long turnPauseMs) {
        KeyController ki = new KeyController(true);
        ActionController ac = new ActionController();

        WindowInput wi = new WindowInput();
        wi.windowClosed = false;
        // closeAppOnClosingWindow = true (standalone demo), followable = true.
        GUI frame = new GUI(game, "Tribes - Followable Game", ki, wi, ac, true, true);
        frame.addWindowListener(wi);
        frame.addKeyListener(ki);

        game.setFollowable(true, turnPauseMs);

        System.out.println("Followable game ready. Press 'START GAME' in the window to begin.");
        game.run(frame, wi);
    }

    private static ArrayList<Agent> getPlayers(Run.PlayerType[] playerTypes) {
        ArrayList<Agent> players = new ArrayList<>();
        long agentSeed = AGENT_SEED == -1 ? System.currentTimeMillis() + new Random().nextInt() : AGENT_SEED;

        if (RUN_VERBOSE) System.out.println("Agents random seed: " + agentSeed);

        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < playerTypes.length; ++i)
            allIds.add(i);

        for (int i = 0; i < playerTypes.length; ++i) {
            Agent ag = Run.getAgent(playerTypes[i], agentSeed);
            assert ag != null;
            ag.setPlayerIDs(i, allIds);
            players.add(ag);
        }
        return players;
    }

    private static Game _prepareGame(Types.TRIBE[] tribes, long levelSeed, Run.PlayerType[] playerTypes, Types.GAME_MODE gameMode) {
        long gameSeed = GAME_SEED == -1 ? System.currentTimeMillis() : GAME_SEED;
        if (RUN_VERBOSE) System.out.println("Game seed: " + gameSeed);

        ArrayList<Agent> players = getPlayers(playerTypes);

        Game game = new Game();
        long levelGenSeed = levelSeed;
        if (levelGenSeed == -1)
            levelGenSeed = System.currentTimeMillis() + new Random().nextInt();

        if (RUN_VERBOSE) System.out.println("Level seed: " + levelGenSeed);

        game.init(players, levelGenSeed, tribes, gameSeed, gameMode);
        return game;
    }

    private static Game _prepareGame(String levelFile, Run.PlayerType[] playerTypes, Types.GAME_MODE gameMode) {
        long gameSeed = GAME_SEED == -1 ? System.currentTimeMillis() : GAME_SEED;
        if (RUN_VERBOSE) System.out.println("Game seed: " + gameSeed);

        ArrayList<Agent> players = getPlayers(playerTypes);

        Game game = new Game();
        game.init(players, levelFile, gameSeed, gameMode);
        return game;
    }

    private static Game _loadGame(Run.PlayerType[] playerTypes, String saveGameFile) {
        long agentSeed = AGENT_SEED == -1 ? System.currentTimeMillis() + new Random().nextInt() : AGENT_SEED;

        ArrayList<Agent> players = new ArrayList<>();
        ArrayList<Integer> allIds = new ArrayList<>();
        for (int i = 0; i < playerTypes.length; ++i)
            allIds.add(i);

        for (int i = 0; i < playerTypes.length; ++i) {
            Agent ag = Run.getAgent(playerTypes[i], agentSeed);
            assert ag != null;
            ag.setPlayerIDs(i, allIds);
            players.add(ag);
        }

        Game game = new Game();
        game.init(players, saveGameFile);
        return game;
    }
}
