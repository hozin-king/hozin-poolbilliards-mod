package com.forthblue.pool.rules;
import com.forthblue.pool.engine.PoolBall;
public class Vsai8Ball extends PoolRule {
    public boolean pvp;
    public float getForce() { return 1500f; }
    public float getRollForce() { return 100f; }
    public float getHoleGravity() { return 3600f; }
    public float getBallRadius() { return 47.15f; }
    public float getTableWidth() { return 2810f; }
    public float getTableHeight() { return 1530f; }
    public float getMinStrength() { return 200f; }
    public float getMaxStrength() { return 4000f; }
    public int getClickTarget(RoundState rs) { return 0; }
    public int getPlayerType(int p) { return 0; }
    public boolean isPVPMode() { return pvp; }
    public boolean isAvailableTarget(RoundState rs, PoolBall[] balls, int i) { return i != 0; }
    public boolean canPut(PoolBall[] balls, int id, float x, float y) { return true; }
}
