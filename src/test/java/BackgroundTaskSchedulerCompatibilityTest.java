import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class BackgroundTaskSchedulerCompatibilityTest {
    @Test
    void legacyFacadeStillSchedulesTasks() throws Exception {
        BackgroundTaskScheduler scheduler = new BackgroundTaskScheduler(1);
        try {
            CountDownLatch ran = new CountDownLatch(1);
            scheduler.scheduleTask(ran::countDown, 0, TimeUnit.MILLISECONDS, 1);
            assertTrue(ran.await(2, TimeUnit.SECONDS));
        } finally {
            scheduler.shutdown();
        }
    }
}
