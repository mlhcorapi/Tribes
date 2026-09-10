package gui;

import core.TechnologyTree;
import core.Types;
import core.actors.Tribe;
import core.game.Game;
import core.game.GameState;
import players.ActionController;
import players.HumanAgent;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import static core.Constants.*;

public class TechView extends JComponent {

    private Dimension size;
    JButton[] techs;
    private GameState gs;
    ActionController ac;
    TechnologyNode[] technologies;
    ArrayList<TechnologyNode> roots, leaves;
    InfoView infoView;
    Game game;

    // --- Tech-purchase flash highlighting ---
    // Number of timer frames a newly researched tech (and the banner) stays highlighted.
    private static final int FLASH_FRAMES = 24;
    private JScrollPane scrollPane;
    private JLabel flashBanner;
    // Per-tribe set of technologies we have already seen researched, so we only flash brand new ones.
    private final HashMap<Integer, HashSet<Types.TECHNOLOGY>> knownTechs = new HashMap<>();
    // Remaining flash frames per technology index (parallel to techs[]).
    private int[] flashRemaining;
    private int bannerRemaining = 0;
    private javax.swing.Timer flashTimer;

    /** Notified when the active tribe researches a new technology (used to show a banner elsewhere). */
    public interface ResearchListener { void onResearch(String tribeName, String techName); }
    private ResearchListener researchListener;
    public void setResearchListener(ResearchListener l) { this.researchListener = l; }

    // TODO: search functionality (i.e. "custom house -> Trade research")
    TechView(Game game, ActionController ac, InfoView infoView)
    {
        this.setLayout(new BorderLayout());
        this.infoView = infoView;
        this.game = game;

        this.ac = ac;
        this.size = new Dimension(GUI_SIDE_PANEL_WIDTH, GUI_TECH_PANEL_HEIGHT);
        JPanel buttons = new JPanel();
        buttons.setPreferredSize(new Dimension(GUI_SIDE_PANEL_WIDTH, GUI_TECH_PANEL_FULL_SIZE));
        GridBagLayout gbl = new GridBagLayout();
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.anchor = GridBagConstraints.NORTH;
        gbc.weighty = 0;
        buttons.setLayout(gbl);

        // Get tech tree structure
        int nTechs = Types.TECHNOLOGY.values().length;
        roots = new ArrayList<>();
        leaves = new ArrayList<>();
        technologies = new TechnologyNode[nTechs];
        for (int i = 0; i < nTechs; i++) {
            technologies[i] = new TechnologyNode(Types.TECHNOLOGY.values()[i]);
            if (technologies[i].isRoot()) {
                roots.add(technologies[i]);
            }
        }
        for (int i = 0; i < nTechs; i++) {
            technologies[i].setChildren();
            technologies[i].getRootIdx(roots);
            if (technologies[i].isLeaf()) {
                leaves.add(technologies[i]);
            }
        }

        flashRemaining = new int[nTechs];

        techs = new JButton[nTechs];
        for (int i = 0; i < nTechs; i++) {
            TechnologyNode node = technologies[i];
            gbc.gridy = node.getRootIdx(roots) + node.getOffsetIdx(leaves) + node.childIdx + node.getSumParentIdx();
            gbc.gridx = node.depth;

            Types.TECHNOLOGY t = node.tech;

            // Info of each research
            String effect = "<h1>" + t.toString() + "</h1>";
            for (Types.BUILDING b: Types.BUILDING.values()) {
                if (b.getTechnologyRequirement() == t) {
                    effect += "<br/>Enables building " + b.toString() + ".";
                }
            }
            for (Types.UNIT u: Types.UNIT.values()) {
                if (u.getTechnologyRequirement() == t && u.spawnable()) {
                    effect += "<br/>Enables spawning " + u.toString() + ".";
                }
            }
            for (Types.RESOURCE r : Types.RESOURCE.values()) {
                if (r.getTechnologyRequirement() == t) {
                    effect += "<br/>Enables gathering " + r.toString() + ".";
                }

            }
            for (Types.ACTION a : Types.ACTION.values()) {
                if (a.getTechnologyRequirement() == t) {
                    effect += "<br/>Enables action " + a.toString() + ".";
                }
            }
//            techEffects[i] = effect;
            String ef = effect;

            JButton button = new JButton(t.name());
            button.setBackground(Color.DARK_GRAY);
            button.addActionListener(e -> {
                infoView.setTechHighlightText(ef);
                if (game.getPlayers()[gs.getActiveTribeID()] instanceof HumanAgent ||
                        !DISABLE_NON_HUMAN_GRID_HIGHLIGHT) {
                    infoView.setTechHighlight(t);
                }
            });
            button.setOpaque(true);
            techs[i] = button;
            buttons.add(techs[i], gbc);
        }

        // Banner at the top that announces a tech purchase (flashes gold for a moment).
        flashBanner = new JLabel(" ", SwingConstants.CENTER);
        flashBanner.setFont(flashBanner.getFont().deriveFont(Font.BOLD, 15f));
        flashBanner.setForeground(new Color(255, 215, 0));
        flashBanner.setOpaque(false);

        scrollPane = new JScrollPane(buttons);
        scrollPane.setPreferredSize(size);
        this.add(flashBanner, BorderLayout.NORTH);
        this.add(scrollPane, BorderLayout.CENTER);

        // Timer that animates the flash. It runs only while something is flashing, then stops.
        flashTimer = new javax.swing.Timer(60, e -> {
            boolean any = false;
            for (int i = 0; i < flashRemaining.length; i++) {
                if (flashRemaining[i] > 0) {
                    flashRemaining[i]--;
                    any = true;
                }
            }
            if (bannerRemaining > 0) {
                bannerRemaining--;
                if (bannerRemaining == 0) {
                    flashBanner.setText(" ");
                    flashBanner.setOpaque(false);
                }
                any = true;
            }
            repaint();  // re-runs paintWithGraphics, which recolors the (still) flashing buttons
            if (!any) flashTimer.stop();
        });
        flashTimer.setRepeats(true);
    }

    /**
     * Resizes the scrollable tech-tree view (used by the followable demo GUI to give it more room).
     */
    public void setViewSize(Dimension d) {
        this.size = d;
        if (scrollPane != null) scrollPane.setPreferredSize(d);
        revalidate();
    }


    public void paintComponent(Graphics gx)
    {
        Graphics2D g = (Graphics2D) gx;
        paintWithGraphics(g);
    }

    private void paintWithGraphics(Graphics2D g)
    {
        if (gs == null) return;

        //For a better graphics, enable this: (be aware this could bring performance issues depending on your HW & OS).
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Tribe t = gs.getActiveTribe();
        if (t != null) {
            TechnologyTree tt = t.getTechTree();
            if (tt != null) {
                // Track which techs this tribe has already researched so we only flash *new* purchases.
                // The first time we see a tribe we seed its baseline silently (no flash).
                int tribeId = gs.getActiveTribeID();
                HashSet<Types.TECHNOLOGY> known = knownTechs.get(tribeId);
                boolean firstSeen = (known == null);
                if (firstSeen) {
                    known = new HashSet<>();
                    knownTechs.put(tribeId, known);
                }

                for (int i = 0; i < Types.TECHNOLOGY.values().length; i++) {
                    Types.TECHNOLOGY opt = Types.TECHNOLOGY.values()[i];
                    boolean researched = tt.isResearched(opt);
                    boolean techRequirement = tt.isResearchable(opt);
                    int starCost = opt.getCost(t.getNumCities(), tt);
                    boolean starRequirement = t.getStars() >= starCost;
                    boolean researchable = techRequirement && starRequirement;

                    // Detect a brand-new research and trigger its flash.
                    if (researched && !known.contains(opt)) {
                        known.add(opt);
                        if (!firstSeen) {
                            flashRemaining[i] = FLASH_FRAMES;
                            if (!flashTimer.isRunning()) flashTimer.start();
                            if (researchListener != null) {
                                // Followable mode shows the message elsewhere (above the scores).
                                researchListener.onResearch(t.getName(), opt.toString());
                            } else {
                                bannerRemaining = FLASH_FRAMES;
                                flashBanner.setText("★  " + t.getName() + " researched " + opt.toString() + "  ★");
                                flashBanner.setOpaque(true);
                                flashBanner.setBackground(new Color(40, 40, 40));
                            }
                        }
                    }

                    if (flashRemaining[i] > 0) {
                        // Pulsing gold highlight to draw attention to the new tech.
                        boolean bright = ((flashRemaining[i] / 3) % 2) == 0;
                        techs[i].setBackground(bright ? new Color(255, 215, 0) : new Color(255, 140, 0));
                        techs[i].setForeground(Color.BLACK);
                        techs[i].setFont(new Font(getFont().getName(), Font.BOLD, getFont().getSize() + 1));
                    } else if (!(researchable || researched)) {
                        techs[i].setBackground(Color.DARK_GRAY);
                        techs[i].setForeground(new Color(176, 183, 181));
                        techs[i].setFont(new Font(getFont().getName(), Font.PLAIN, getFont().getSize()));
                        if (techRequirement) {
                            techs[i].setForeground(new Color(255, 167, 163));
                        }
                    } else {
                        if (researched) {
                            techs[i].setBackground(new Color(4, 77, 118));
                            techs[i].setForeground(new Color(128, 255, 255));
                            techs[i].setFont(new Font(getFont().getName(), Font.BOLD, getFont().getSize()));
                        } else {
                            techs[i].setBackground(Color.DARK_GRAY);
                            techs[i].setForeground(new Color(78, 255, 113));
                            techs[i].setFont(new Font(getFont().getName(), Font.PLAIN, getFont().getSize()));
                        }
                    }
                }
            }
        }
    }


    void paint(GameState gs)
    {
        this.gs = gs;
    }

    /**
     * Gets the dimensions of the window.
     * @return the dimensions of the window.
     */
    public Dimension getPreferredSize() {
        return new Dimension(size.width, size.height + 28);  // +28 for the flash banner row
    }

    static class TechnologyNode {
        static HashSet<TechnologyNode> nodes;
        static int identifier = 0;

        Types.TECHNOLOGY tech;
        TechnologyNode parent;
        TechnologyNode[] children;
        int depth = -1;
        int rootIdx = -1;
        int offsetIdx = -1;
        int childIdx = 0;  // idx of this node in the children array of the parent
        int id;

        TechnologyNode(Types.TECHNOLOGY t) {
            if (nodes == null) {
                nodes = new HashSet<>();
            }

            if (t != null) {
                this.id = identifier++;
                this.tech = t;
                this.parent = findParent(t.getParentTech());
                if (this.parent != null) {
                    nodes.add(this.parent);
                }

                if (isRoot()) {
                    depth = 0;
                } else {
                    depth = parent.depth + 1;
                }

                nodes.add(this);
            }
        }

        void setChildren() {
            ArrayList<Types.TECHNOLOGY> child = tech.getChildTech();
            int nChildren = child.size();
            this.children = new TechnologyNode[nChildren];
            int idx = 0;
            for (TechnologyNode n: nodes) {
                if (child.contains(n.tech)) {
                    n.childIdx = idx;
                    children[idx++] = n;
                }
            }
        }

        TechnologyNode findParent(Types.TECHNOLOGY t) {
            if (t == null) return null;
            for (TechnologyNode n: nodes) {
                if (n.tech == t) {
                    return n;
                }
            }
            return new TechnologyNode(t);
        }

        boolean isLeaf() {
            return children.length == 0;
        }

        boolean isRoot() {
            return parent == null;
        }

        int getRootIdx(ArrayList<TechnologyNode> roots) {
            if (rootIdx == -1) {
                TechnologyNode n = this;
                while (n != null) {
                    if (n.isRoot()) {
                        rootIdx = roots.indexOf(n);
                        break;
                    } else {
                        n = n.parent;
                    }
                }
            }
            return rootIdx;
        }

        int getOffsetIdx(ArrayList<TechnologyNode> leaves) {
            if (offsetIdx == -1) {
                offsetIdx = 0;
                for (TechnologyNode l : leaves) {
                    if (l.rootIdx <= rootIdx) {
                        offsetIdx++;
                    }
                }
            }
            return offsetIdx;
        }

        int getSumParentIdx() {
            int s = 0;
            TechnologyNode node = this;
            while (node.parent != null) {
                s += node.parent.childIdx;
                node = node.parent;
            }
            return s;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TechnologyNode that = (TechnologyNode) o;
            return id == that.id;
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public String toString() {
            return tech.toString();
        }
    }
}
