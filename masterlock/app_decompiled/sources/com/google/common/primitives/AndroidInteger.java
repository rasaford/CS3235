package com.google.common.primitives;

import com.google.common.base.Preconditions;
import javax.annotation.CheckForNull;

final class AndroidInteger {
    @CheckForNull
    static Integer tryParse(String str) {
        return tryParse(str, 10);
    }

    @CheckForNull
    static Integer tryParse(String str, int i) {
        Preconditions.checkNotNull(str);
        int i2 = 1;
        Preconditions.checkArgument(i >= 2, "Invalid radix %s, min radix is %s", Integer.valueOf(i), Integer.valueOf(2));
        Preconditions.checkArgument(i <= 36, "Invalid radix %s, max radix is %s", Integer.valueOf(i), Integer.valueOf(36));
        int length = str.length();
        if (length == 0) {
            return null;
        }
        boolean z = str.charAt(0) == '-';
        if (!z) {
            i2 = 0;
        } else if (1 == length) {
            return null;
        }
        return tryParse(str, i2, i, z);
    }

    @CheckForNull
    private static Integer tryParse(String str, int i, int i2, boolean z) {
        int i3 = Integer.MIN_VALUE / i2;
        int length = str.length();
        int i4 = 0;
        while (i < length) {
            int i5 = i + 1;
            int digit = Character.digit(str.charAt(i), i2);
            if (digit == -1 || i3 > i4) {
                return null;
            }
            int i6 = (i4 * i2) - digit;
            if (i6 > i4) {
                return null;
            }
            i4 = i6;
            i = i5;
        }
        if (!z) {
            i4 = -i4;
            if (i4 < 0) {
                return null;
            }
        }
        if (i4 > Integer.MAX_VALUE || i4 < Integer.MIN_VALUE) {
            return null;
        }
        return Integer.valueOf(i4);
    }

    private AndroidInteger() {
    }
}
