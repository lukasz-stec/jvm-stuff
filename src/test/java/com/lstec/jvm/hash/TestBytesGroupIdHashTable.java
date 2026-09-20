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

public class TestBytesGroupIdHashTable
{
    /** (expectedGroupCount, expectedKeyLength) -> table */
    private static final List<BiFunction<Integer, Integer, BytesGroupIdHashTable>> FACTORIES = List.of(
            SimpleBytesGroupIdHashTable::new,
            InlineKeyBytesGroupIdHashTable::new);

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
