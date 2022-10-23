package com.lstec.jvm.hash.jug;

public class CountAggregation_01
        implements CountAggregation
{
    private int collisions;

    public CountAggregation_01(int expectedCount)
    {
        int size = expectedCount * 2;
        this.hashTable = new Object[size];
        this.count = new long[size];
    }

    private Object[] hashTable;
    private long[] count;

    @Override
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
        return Math.abs(row.hashCode()) % hashTable.length;
    }

    @Override
    public void batchIncrementCount(Object[] rows)
    {
        for (Object row : rows) {
            incrementCount(row);
        }
    }

    @Override
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

    @Override
    public int getCollisions()
    {
        return collisions;
    }
}
