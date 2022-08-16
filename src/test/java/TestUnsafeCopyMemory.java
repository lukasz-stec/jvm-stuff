import sun.misc.Unsafe;

import java.lang.reflect.Field;

public class TestUnsafeCopyMemory
{

    static final Unsafe unsafe;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args)
    {
        byte[] bytes = new byte[0];
        for (int j = 0; j < 1000; j++) {
            duplicateBytesUnsafe(null, 0, 2000, bytes, 0, 0);
        }
    }

    private static void duplicateBytesUnsafe(Object src, int srcAddress, int count, byte[] dest, int destAddress, int length)
    {
        for (int i = 0; i < count; i++) {
            unsafe.copyMemory(src, srcAddress, dest, destAddress, length);
        }
    }
}
