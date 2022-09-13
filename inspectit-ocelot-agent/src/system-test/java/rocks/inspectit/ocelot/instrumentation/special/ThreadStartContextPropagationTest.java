package rocks.inspectit.ocelot.instrumentation.special;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.context.Scope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import rocks.inspectit.ocelot.instrumentation.InstrumentationSysTestBase;
import rocks.inspectit.ocelot.utils.TestUtils;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

public class ThreadStartContextPropagationTest extends InstrumentationSysTestBase {

    /**
     * Abstract thread class.
     */
    private abstract class AbstractThread extends Thread {

        protected Runnable run;

        public AbstractThread(Runnable runnable) {
            run = runnable;
        }

        @Override
        public synchronized void start() {
            super.start();
        }

        @Override
        public void run() {
            run.run();
        }
    }

    private class SubThread extends AbstractThread {

        public SubThread(Runnable runnable) {
            super(runnable);
            setName("dummy-thread");
        }
    }

    @BeforeAll
    static void waitForInstrumentation() {
        TestUtils.waitForClassInstrumentations(Arrays.asList(AbstractThread.class, Thread.class), false, 15, TimeUnit.SECONDS);
    }

    @Test
    public void verifyContextPropagationViaAbstractThreads() throws InterruptedException {
        long rand = System.nanoTime();
        String tagKey = "test-tag-key-" + rand;
        String tagValue = "test-tag-value-" + rand;
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Map<String, BaggageEntry>> refTags = new AtomicReference<>();

        Thread thread = new SubThread(() -> {
            refTags.set(Baggage.current().asMap());
            latch.countDown();
        });

        try (Scope s = Baggage.current().toBuilder().put(tagKey, tagValue).build().makeCurrent()) {
            thread.start();
        }

        latch.await(5, TimeUnit.SECONDS);

        assertThat(refTags.get()).hasSize(1).extracting("key", "value").contains(tuple(tagKey, tagValue));
    }

    @Test
    public void verifyContextProgapation() throws InterruptedException {
        TagKey tagKey = TagKey.create("test-tag-key");
        TagValue tagValue = TagValue.create("test-tag-value");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Iterator<Tag>> refTags = new AtomicReference<>();

        Thread thread = new Thread(() -> {
            Iterator<Tag> iter = InternalUtils.getTags(tagger.getCurrentTagContext());
            refTags.set(iter);
            latch.countDown();
        });

        try (Scope s = tagger.currentBuilder().putLocal(tagKey, tagValue).buildScoped()) {
            thread.start();
        }

        latch.await(5, TimeUnit.SECONDS);

        assertThat(refTags.get()).hasSize(1).extracting("key", "value").contains(tuple(tagKey, tagValue));
    }

    @Test
    public void verifyContextProgapationUsingSubClasses() throws InterruptedException {
        TagKey tagKey = TagKey.create("test-tag-key");
        TagValue tagValue = TagValue.create("test-tag-value");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Iterator<Tag>> refTags = new AtomicReference<>();

        Thread thread = new Thread(() -> {
            Iterator<Tag> iter = InternalUtils.getTags(tagger.getCurrentTagContext());
            refTags.set(iter);
            latch.countDown();
        }) {
            @Override
            public synchronized void start() {
                super.start();
            }
        };

        try (Scope s = tagger.currentBuilder().putLocal(tagKey, tagValue).buildScoped()) {
            thread.start();
        }

        latch.await(5, TimeUnit.SECONDS);

        assertThat(refTags.get()).hasSize(1).extracting("key", "value").contains(tuple(tagKey, tagValue));
    }

    @Test
    public void noContextProgapationViaConstructor() throws InterruptedException {
        TagKey tagKey = TagKey.create("test-tag-key");
        TagValue tagValue = TagValue.create("test-tag-value");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Iterator<Tag>> refTags = new AtomicReference<>();

        Thread thread;

        try (Scope s = tagger.currentBuilder().putLocal(tagKey, tagValue).buildScoped()) {
            thread = new Thread(() -> {
                Iterator<Tag> iter = InternalUtils.getTags(tagger.getCurrentTagContext());
                refTags.set(iter);
                latch.countDown();
            });
        }

        thread.start();

        latch.await(5, TimeUnit.SECONDS);

        assertThat(refTags.get()).hasSize(0);
    }

    @Test
    public void noCorrelationInExecutor() throws Exception {
        TagKey tagKey = TagKey.create("tag_key");
        TagValue tagValue = TagValue.create("tag_value");

        AtomicReference<Iterator<Tag>> refTagsInner = new AtomicReference<>();
        Runnable runnable = HelperClasses.getRunnableAsNamed(refTagsInner);

        ExecutorService executorService = Executors.newSingleThreadExecutor();

        try (Scope s = tagger.currentBuilder().putLocal(tagKey, tagValue).buildScoped()) {
            executorService.submit(runnable);
        }

        AtomicReference<Iterator<Tag>> refTagsOuter = new AtomicReference<>();
        Runnable runnableSecond = HelperClasses.getRunnableAsNamed(refTagsOuter);
        Future<?> taskFuture = executorService.submit(runnableSecond); // have to be empty!
        taskFuture.get();

        assertThat(refTagsInner.get()).hasSize(1).extracting("key", "value").contains(tuple(tagKey, tagValue));
        assertThat(refTagsOuter.get()).isEmpty();
    }
}
