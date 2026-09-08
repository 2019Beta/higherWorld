package org.devt.higherworld.world;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;
import org.devt.higherworld.storage.CubePos;

/** Optional JFR spans for separating generation, loading and publication costs. */
@Name("higherworld.CubeWork")
@Label("HigherWorld cube work")
@Category("HigherWorld")
@StackTrace(false)
@Threshold("1 ms")
public final class CubeWorkEvent extends Event implements AutoCloseable {
    private static final EventType TYPE = EventType.getEventType(CubeWorkEvent.class);
    @Label("Stage") public String stage;
    public int cubeX;
    public int cubeY;
    public int cubeZ;

    public static CubeWorkEvent start(String stage, CubePos pos) {
        if (!TYPE.isEnabled()) return null;
        CubeWorkEvent event = new CubeWorkEvent();
        event.stage = stage;
        event.cubeX = pos.x();
        event.cubeY = pos.y();
        event.cubeZ = pos.z();
        event.begin();
        return event;
    }

    @Override
    public void close() {
        end();
        commit();
    }
}
