import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import owt.base.ContextInitialization;
import owt.base.LocalStream;

@RunWith(AndroidJUnit4.class)
public class LocalStreamTest {
    static {
        ContextInitialization.create().setApplicationContext(
                InstrumentationRegistry.getTargetContext()).initialize();
    }

    // TODO: add more test cases.
    @Test
    public void testCreateLocalStream() {
        new LocalStream(new MockVideoCapturer());
    }
}
