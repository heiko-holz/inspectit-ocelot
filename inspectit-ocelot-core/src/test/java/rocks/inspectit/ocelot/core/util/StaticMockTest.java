package rocks.inspectit.ocelot.core.util;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest({StaticMockTestUtils.class})
public class StaticMockTest {

    // make sure to use org.junit.Test for PowerMock
    @Test
    public void spyStatic() {
        PowerMockito.spy(StaticMockTestUtils.class);
        StaticMockTestUtils.doThisThat("henlo", "thizz is doge");
        PowerMockito.verifyStatic(StaticMockTestUtils.class);
        StaticMockTestUtils.doThis("henlo");
        PowerMockito.verifyStatic(StaticMockTestUtils.class);
        StaticMockTestUtils.doThat("thizz is doge");
        PowerMockito.verifyStatic(StaticMockTestUtils.class);
        StaticMockTestUtils.doThisThat("henlo", "thizz is doge");
        PowerMockito.verifyNoMoreInteractions();
    }

}

class StaticMockTestUtils {

    public static void doThis(String str) {
        System.out.println("this=" + str);
    }

    public static void doThat(String str) {
        System.out.println("that=" + str);
    }

    public static void doThisThat(String thizz, String that) {
        doThis(thizz);
        doThat(that);
    }

    public static String getString() {
        return "I <3 ocelot";
    }
}
