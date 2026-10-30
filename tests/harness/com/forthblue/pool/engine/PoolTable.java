package com.forthblue.pool.engine;
import com.forthblue.pool.rules.PoolRule;
public class PoolTable {
    public PoolBall[] balls;
    public Blocker[] blockers;
    public PoolHole[] holes;
    public boolean isFirstShoot;
    public PoolRule.RoundState roundstate;
    public int putFreeballCalls;
    public int clickCalls;
    public float[] lastClick;
    public void putFreeball() {
        putFreeballCalls++;
        if (roundstate != null) roundstate.state = 1;
    }
    public void click(float strength, float spinX, float spinY, float angle) {
        clickCalls++;
        lastClick = new float[]{strength, spinX, spinY, angle};
    }
}
