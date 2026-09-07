package org.devt.higherworld.world;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.Test;

class CubeStreamPayloadTest {
    @Test
    void streamIdentityAndCumulativeCountersSurviveNetworkRoundTrip() {
        RegistryByteBuf buffer = new RegistryByteBuf(Unpooled.buffer(), DynamicRegistryManager.EMPTY);
        try {
            CubeStreamStartPayload start = new CubeStreamStartPayload(123456789L);
            CubeStreamStartPayload.CODEC.encode(buffer, start);
            assertEquals(start, CubeStreamStartPayload.CODEC.decode(buffer));
            CubeStreamFeedbackPayload feedback = new CubeStreamFeedbackPayload(
                    start.streamId(), 9_000_000_000L, 1024, 7, 123, 32, 5);
            CubeStreamFeedbackPayload.CODEC.encode(buffer, feedback);
            assertEquals(feedback, CubeStreamFeedbackPayload.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally { buffer.release(); }
    }
}
