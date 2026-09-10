package players.ExperimentalEMCTS;

import core.actions.Action;
import core.game.GameState;

import java.util.ArrayList;

public class ExperimentalEMCTSTreeNode {

    private ArrayList<Action> sequence;
    private ExperimentalEMCTSTreeNode parent;
    private ArrayList<ExperimentalEMCTSTreeNode> children;
    private double value; // the heuristic value of the node
    private double score;// the exploration score of the node
    private int times_visited;
    private GameState gs;

    public ExperimentalEMCTSTreeNode(ArrayList<Action> sequence, ExperimentalEMCTSTreeNode parent){
        this.sequence = sequence;
        this.parent = parent;
        children = new ArrayList<>();
    }


    public void addChild(ExperimentalEMCTSTreeNode child){
        this.children.add(child);
    }

    public double getValue() {
        return value;
    }

    public ExperimentalEMCTSTreeNode getParent() {
        return parent;
    }

    public ArrayList<Action> getSequence() {
        return sequence;
    }

    public ArrayList<ExperimentalEMCTSTreeNode> getChildren() {
        return children;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public double getScore() {
        return score;
    }

    public int getTimes_visited() {
        return times_visited;
    }

    public void setScore(int score) {
        this.score = score;
    }

    private int getParentVisited(){
        if(parent == null){ // thus root node
            return 1;
        }else{
            return parent.getTimes_visited();
        }
    }

    public void setSequence(ArrayList<Action> sequence) {
        this.sequence = sequence;
    }

    public void visited(){ times_visited++; } // if needs be

    public void refreshScore(float bias){
        this.score = this.value;
    }

    public void setGs(GameState gs) {
        this.gs = gs;
    }

    public GameState getGs() {
        return gs;
    }

    public Action returnNext(){
        Action action = sequence.get(0);
        sequence.remove(0);
        return action;
    }
    //used to erase links to all other objects this JVM should clear it
    public void unlink(){
        this.parent = null;
    }

    public void disconnectParent() {
        this.parent = null;
    }
}