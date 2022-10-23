package com.lstec.jvm.hash.jug;

public class CountAggregation_02
        implements CountAggregation
{
    private int collisions;

    public CountAggregation_02(int expectedCount)
    {
        int size = Math.max(expectedCount * 2, 1024);
        this.hashTable = new Object[size];
        this.count = new long[size];
    }

    private Object[] hashTable;
    private long[] count;

    public void incrementCount(Object row)
    {
        int position = calculatePosition(row);
        while (hashTable[position] != null) {
            if (hashTable[position].equals(row)) {
                // found an existing group
                count[position]++;
                return;
            }
            collisions++;
            // increment position
            position = (position + 1) % hashTable.length;
        }

        // existing group not found
        hashTable[position] = row;
        count[position] = 1;
    }

    private int calculatePosition(Object row)
    {
        return Math.abs(CountAggregation.hash(row.hashCode()) % hashTable.length);
    }

    public void batchIncrementCount(Object[] rows)
    {
        for (Object row : rows) {
            incrementCount(row);
        }
    }

    public long getCount(Object row)
    {
        int position = calculatePosition(row);
        while (hashTable[position] != null) {
            if (hashTable[position].equals(row)) {
                return count[position];
            }
            // increment position
            position = (position + 1) % hashTable.length;
        }
        return -1;
    }

    public int getCollisions()
    {
        return collisions;
    }
}
