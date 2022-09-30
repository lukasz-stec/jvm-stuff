package com.lstec.jvm.hash;

public class MultiColumnHashMap
{
    public MultiColumnHashMap(int size)
    {
        this.hashTable = new Object[size];
        this.count = new long[size];
    }

    private Object[] hashTable;
    private long[] count;

    public void incrementCount(Object row)
    {
        int position = row.hashCode() % hashTable.length;
        while (hashTable[position] != null) {
            if (hashTable[position].equals(row)) {
                // found an existing group
                count[position]++;
                return;
            }
            // increment position
            position = (position + 1) % hashTable.length;
        }

        // existing group not found
        hashTable[position] = row;
        count[position] = 1;
    }

    public void incrementCount(Object[] rows)
    {
        for (Object row : rows) {
            incrementCount(row);
        }
    }

    public long getCount(Object row)
    {
        int position = row.hashCode() % hashTable.length;
        while (hashTable[position] != null) {
            if (hashTable[position].equals(row)) {
                return count[position];
            }
            // increment position
            position = (position + 1) % hashTable.length;
        }
        return -1;
    }

    public static void main(String[] args)
    {
        MultiColumnHashMap map = new MultiColumnHashMap(10);
        map.incrementCount("1");
        map.incrementCount("1");
        map.incrementCount("2");
        map.incrementCount("2");
        map.incrementCount("2");
        System.out.println(map.getCount("1"));
        System.out.println(map.getCount("2"));
        System.out.println(map.getCount("3"));
    }
}
