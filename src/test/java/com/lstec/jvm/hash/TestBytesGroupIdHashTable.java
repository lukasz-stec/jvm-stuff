package com.lstec.jvm.hash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestBytesGroupIdHashTable
{
    /** (expectedGroupCount, expectedKeyLength) -> table */
    private static final List<BiFunction<Integer, Integer, BytesGroupIdHashTable>> FACTORIES = List.of(
            SimpleBytesGroupIdHashTable::new,
            InlineKeyBytesGroupIdHashTable::new,
            PipelinedBytesGroupIdHashTable::new,
            // batch sizes that do not divide the block size, so batches straddle block ends
            (groups, keyLength) -> new PipelinedBytesGroupIdHashTable(groups, keyLength, 1),
            (groups, keyLength) -> new PipelinedBytesGroupIdHashTable(groups, keyLength, 7),
            // sized far too small, so it rehashes repeatedly while batches are in flight
            (groups, keyLength) -> new PipelinedBytesGroupIdHashTable(1, keyLength, 64),
            // no tag at all: every occupied slot on the probe path is compared in full
            (groups, keyLength) -> new TaggedBytesGroupIdHashTable(groups, keyLength, 0, groups),
            (groups, keyLength) -> new TaggedBytesGroupIdHashTable(groups, keyLength, 8, groups),
            (groups, keyLength) -> new TaggedBytesGroupIdHashTable(groups, keyLength, 32, groups),
            // sized 64x too small, so the table rehashes repeatedly: with 32 bits from the slot,
            // and with 8 bits, where every key has to be read back and rehashed
            (groups, keyLength) -> new TaggedBytesGroupIdHashTable(groups, keyLength, 8, Math.max(1, groups / 64)),
            (groups, keyLength) -> new TaggedBytesGroupIdHashTable(groups, keyLength, 32, Math.max(1, groups / 64)));

    @Test
    public void testAllImplementationsAgreeRowByRow()
    {
        // the other assertions only check that ids are consistent and dense, which a table that
        // numbers groups in the wrong order still satisfies; this pins the order itself
        // fixed length keys, so the length in the slot filters nothing and every colliding
        // probe becomes a candidate that has to be confirmed
        List<byte[]> keys = distinctKeys(20_000, 24, 24);
        Random random = new Random(21);
        List<List<byte[]>> blocks = new ArrayList<>();
        for (int block = 0; block < 60; block++) {
            List<byte[]> rows = new ArrayList<>();
            for (int i = 0; i < 700; i++) {
                rows.add(keys.get(random.nextInt(keys.size())));
            }
            blocks.add(rows);
        }

        int[][] reference = null;
        for (BiFunction<Integer, Integer, BytesGroupIdHashTable> factory : FACTORIES) {
            // sized well under the real group count, so the tables grow while batches are live
            BytesGroupIdHashTable hashTable = factory.apply(5000, 24);
            int[][] actual = new int[blocks.size()][];
            for (int block = 0; block < blocks.size(); block++) {
                actual[block] = groupIds(hashTable, blocks.get(block));
            }
            if (reference == null) {
                reference = actual;
            }
            else {
                assertThat(actual).as("group ids must match the first implementation exactly").isEqualTo(reference);
            }
        }
    }

    @Test
    public void testRejectsImpossibleTagWidth()
    {
        for (int hashBits : new int[] {-1, 33}) {
            assertThatThrownBy(() -> new TaggedBytesGroupIdHashTable(16, 8, hashBits, 16))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("hashBits");
        }
    }

    @Test
    public void testRehashPreservesGroupsWhateverTheTagWidth()
    {
        // one entry per tag width, all grown from a single slot
        for (int hashBits : new int[] {0, 8, 16, 32}) {
            TaggedBytesGroupIdHashTable hashTable = new TaggedBytesGroupIdHashTable(5000, 24, hashBits, 1);
            assertGroupIds(hashTable, distinctKeys(5000, 4, 40), new Random(3), 20_000);
            assertThat(hashTable.getRehashCount()).as("tag width %s should have grown", hashBits).isGreaterThan(5);
        }
    }

    @Test
    public void testGroupIds()
    {
        for (int groupCount : new int[] {1, 4, 1000, 50_000}) {
            assertGroupIds(groupCount, 4, 32, groupCount);
        }
    }

    @Test
    public void testGrowth()
    {
        // sized for far fewer groups and much shorter keys than it gets, so both the slot array
        // and the key arena have to grow repeatedly
        assertGroupIds(20_000, 8, 64, 1);
    }

    @Test
    public void testShortAndEmptyKeys()
    {
        assertGroupIds(500, 0, 3, 500);
    }

    @Test
    public void testKeysSharingLongPrefix()
    {
        // same 64 byte prefix, differing only in the last bytes, so every comparison runs long
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            byte[] key = new byte[64 + (i % 5)];
            for (int j = 0; j < 64; j++) {
                key[j] = (byte) 'a';
            }
            for (int j = 64; j < key.length; j++) {
                key[j] = (byte) (i >>> ((j - 64) * 8));
            }
            key[63] = (byte) i;
            key[62] = (byte) (i >>> 8);
            keys.add(key);
        }
        assertGroupIds(keys, new Random(5), 20_000, 1000, 64);
    }

    @Test
    public void testRepeatedAcrossBlocks()
    {
        for (BiFunction<Integer, Integer, BytesGroupIdHashTable> factory : FACTORIES) {
            // the same key must keep its id when it reappears in a later block
            BytesGroupIdHashTable hashTable = factory.apply(16, 8);
            byte[] a = "alpha".getBytes(StandardCharsets.UTF_8);
            byte[] b = "beta".getBytes(StandardCharsets.UTF_8);

            int[] first = groupIds(hashTable, List.of(a, b, a));
            int[] second = groupIds(hashTable, List.of(b, a, b));

            assertThat(first).containsExactly(0, 1, 0);
            assertThat(second).containsExactly(1, 0, 1);
            assertThat(hashTable.getGroupCount()).isEqualTo(2);
        }
    }

    @Test
    public void testKeysAroundTheInlineBoundary()
    {
        // lengths either side of the 16 byte limit, where a key stops being covered by the
        // head and tail words and has to fall back to the arena
        List<byte[]> keys = new ArrayList<>();
        for (int length = 0; length <= 40; length++) {
            for (int variant = 0; variant < 3; variant++) {
                byte[] key = new byte[length];
                for (int i = 0; i < length; i++) {
                    key[i] = (byte) (i * 7 + variant);
                }
                if (length > 0) {
                    key[length - 1] = (byte) variant;
                }
                keys.add(key);
            }
        }
        // length 0 has no room for a variant, so its three copies are one key
        assertGroupIds(keys, new Random(11), 8000, keys.size() - 2, 16);
    }

    @Test
    public void testLongKeysSharingHeadAndTail()
    {
        // longer than the inline limit and identical in the first and last 8 bytes, so only a
        // full comparison of the middle can tell them apart
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            byte[] key = new byte[40];
            for (int j = 0; j < 8; j++) {
                key[j] = (byte) 'H';
                key[key.length - 1 - j] = (byte) 'T';
            }
            key[20] = (byte) i;
            key[21] = (byte) (i >>> 8);
            keys.add(key);
        }
        assertGroupIds(keys, new Random(12), 5000, 500, 40);
    }

    private static List<byte[]> distinctKeys(int count, int minLength, int maxLength)
    {
        Random random = new Random(count);
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] key = new byte[minLength == maxLength
                    ? minLength
                    : Math.max(minLength, 4 + random.nextInt(maxLength - 3))];
            for (int j = 0; j < key.length; j++) {
                key[j] = (byte) (j < 4 ? i >>> (j * 8) : random.nextInt());
            }
            keys.add(key);
        }
        return keys;
    }

    private static void assertGroupIds(int groupCount, int minLength, int maxLength, int expectedGroupCount)
    {
        Random random = new Random(groupCount);
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < groupCount; i++) {
            // the group index goes in first so keys are distinct whatever the random tail is
            int length = Math.max(minLength, 4 + random.nextInt(Math.max(1, maxLength - 3)));
            byte[] key = new byte[Math.min(length, maxLength)];
            for (int j = 0; j < key.length; j++) {
                key[j] = (byte) (j < 4 ? i >>> (j * 8) : random.nextInt());
            }
            keys.add(key);
        }
        assertGroupIds(keys, random, 6000, expectedGroupCount, 8);
    }

    private static void assertGroupIds(List<byte[]> keys, Random random, int rows, int expectedGroupCount, int expectedKeyLength)
    {
        for (BiFunction<Integer, Integer, BytesGroupIdHashTable> factory : FACTORIES) {
            assertGroupIds(factory.apply(expectedGroupCount, expectedKeyLength), keys, new Random(random.nextLong()), rows);
        }
    }

    private static void assertGroupIds(BytesGroupIdHashTable hashTable, List<byte[]> keys, Random random, int rows)
    {
        Map<String, Integer> expected = new HashMap<>();
        int blockSize = 700; // not a divisor of the row count
        List<byte[]> block = new ArrayList<>();
        for (int row = 0; row <= rows; row++) {
            if (row == rows || block.size() == blockSize) {
                int[] groupIds = groupIds(hashTable, block);
                for (int i = 0; i < block.size(); i++) {
                    String key = new String(block.get(i), StandardCharsets.ISO_8859_1);
                    Integer previous = expected.putIfAbsent(key, groupIds[i]);
                    assertThat(groupIds[i])
                            .as("group id for a key already seen")
                            .isEqualTo(previous == null ? groupIds[i] : previous);
                }
                block = new ArrayList<>();
                if (row == rows) {
                    break;
                }
            }
            block.add(keys.get(random.nextInt(keys.size())));
        }

        assertThat(hashTable.getGroupCount()).isEqualTo(expected.size());
        // ids must be dense: every value in 0..groupCount is used exactly once
        assertThat(expected.values().stream().sorted().toList())
                .isEqualTo(java.util.stream.IntStream.range(0, expected.size()).boxed().toList());
    }

    private static int[] groupIds(BytesGroupIdHashTable hashTable, List<byte[]> keys)
    {
        int totalLength = keys.stream().mapToInt(key -> key.length).sum();
        byte[] slice = new byte[totalLength];
        int[] offsets = new int[keys.size() + 1];
        int offset = 0;
        for (int i = 0; i < keys.size(); i++) {
            byte[] key = keys.get(i);
            System.arraycopy(key, 0, slice, offset, key.length);
            offset += key.length;
            offsets[i + 1] = offset;
        }
        int[] groupIds = new int[keys.size()];
        hashTable.putBlock(new VariableWidthBlock(slice, offsets, keys.size()), groupIds);
        return groupIds;
    }
}
