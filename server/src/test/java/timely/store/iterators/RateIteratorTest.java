package timely.store.iterators;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.SortedMapIterator;
import org.junit.Before;
import org.junit.Test;

import timely.adapter.accumulo.MetricAdapter;
import timely.model.Metric;
import timely.model.Tag;

public class RateIteratorTest extends IteratorTestBase {

    private TreeMap<Key, Value> table = new TreeMap<Key, Value>();
    private static final List<Tag> tags = new ArrayList<>();
    static {
        tags.add(new Tag("rack", "r1"));
    }

    @Before
    public void setup() {
        long ts = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            ts += 1000;
            Metric m = new Metric("sys.cpu.user", ts, i * 1.0D, tags);
            byte[] row = MetricAdapter.encodeRowKey(m);
            Key k = new Key(row, tags.get(0).join().getBytes(StandardCharsets.UTF_8), new byte[0], new byte[0], ts);
            Value v = new Value(MetricAdapter.encodeValue(m.getValue().getMeasure()));
            table.put(k, v);
        }
    }

    @Test
    public void testConstantTimeRate() throws Exception {
        SortedMapIterator source = new SortedMapIterator(table);
        RateIterator iter = new RateIterator();
        IteratorSetting settings = new IteratorSetting(100, RateIterator.class);
        iter.init(source, settings.getOptions(), SCAN_IE);
        iter.seek(new Range(), EMPTY_COL_FAMS, true);
        assertTrue(iter.hasTop());
        for (int i = 0; i < 99; i++) {
            System.out.println(i);
            assertTrue(iter.hasTop());
            assertEquals(0.001D, MetricAdapter.decodeValue(iter.getTopValue().get()), 0.0D);
            iter.next();
        }
        assertFalse(iter.hasTop());
    }

    @Test
    public void testRateWithTimeJitter() throws Exception {
        table.clear();
        Random r = new Random(111131131L);
        long ts = System.currentTimeMillis();
        for (int i = 1; i <= 100; i++) {
            ts += 1000 + r.nextInt(100);
            Metric m = new Metric("sys.cpu.user", ts, i * 1.0D, tags);
            byte[] row = MetricAdapter.encodeRowKey(m);
            Key k = new Key(row, tags.get(0).join().getBytes(StandardCharsets.UTF_8), new byte[0], new byte[0], ts);
            Value v = new Value(MetricAdapter.encodeValue(m.getValue().getMeasure()));
            table.put(k, v);
        }

        SortedMapIterator source = new SortedMapIterator(table);
        source.seek(new Range(), EMPTY_COL_FAMS, true);
        long prevTs = -1L;
        Double prevValue = null;
        List<Double> expected = new ArrayList<>();
        while (source.hasTop()) {
            Key k = source.getTopKey();
            Value v = source.getTopValue();
            if (prevTs != -1L) {
                Double thisValue = MetricAdapter.decodeValue(v.get());
                expected.add((thisValue + (prevValue * -1)) / (k.getTimestamp() - prevTs));
            }
            prevTs = k.getTimestamp();
            prevValue = MetricAdapter.decodeValue(v.get());
            source.next();
        }

        assertEquals(99, expected.size());
        source = new SortedMapIterator(table);
        RateIterator iter = new RateIterator();
        IteratorSetting settings = new IteratorSetting(100, RateIterator.class);
        iter.init(source, settings.getOptions(), SCAN_IE);
        iter.seek(new Range(), EMPTY_COL_FAMS, true);
        assertTrue(iter.hasTop());
        for (int i = 0; i < 99; i++) {
            assertTrue(iter.hasTop());
            assertEquals(expected.get(i), MetricAdapter.decodeValue(iter.getTopValue().get()), 0.0D);
            iter.next();
        }
        assertFalse(iter.hasTop());
    }

}
