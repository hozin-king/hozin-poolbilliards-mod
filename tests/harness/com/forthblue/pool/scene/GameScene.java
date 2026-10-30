package com.forthblue.pool.scene;
import com.forthblue.pool.engine.PoolTable;
import java.util.ArrayList;
public class GameScene {
    public static class Dot {
        public float lastX, lastY;
        public int setCalls;
        public long lastSetMs;
        public void setPosition(float x, float y) { lastX = x; lastY = y; setCalls++; lastSetMs = System.currentTimeMillis(); }
    }
    private float aimAngle, aimStrength, aimPx, aimPy;
    public Dot bigPPosition = new Dot();
    public PoolTable table;
    public int shootCalls;
    public long shootMs;
    public final ArrayList<String> events = new ArrayList<String>();
    public float getAimAngle() { return aimAngle; }
    public void setAimAngle(float a) { aimAngle = a; }
    public void calcAimTarget() { }
    public void shoot(boolean b) {
        shootCalls++;
        shootMs = System.currentTimeMillis();
        events.add("shoot");
        // jalur resmi game: click dengan nilai state saat ini, lalu reset
        table.click(aimStrength, aimPx, aimPy, aimAngle);
        aimStrength = 0f; aimPx = 0f; aimPy = 0f;
    }
    public float peekAimStrength() { return aimStrength; }
    public float peekAimPx() { return aimPx; }
    public float peekAimPy() { return aimPy; }
}
